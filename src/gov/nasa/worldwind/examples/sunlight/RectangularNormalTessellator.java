package gov.nasa.worldwind.examples.sunlight;

import com.sun.opengl.util.BufferUtil;
import gov.nasa.worldwind.Configuration;
import gov.nasa.worldwind.View;
import gov.nasa.worldwind.WWObjectImpl;
import gov.nasa.worldwind.WorldWind;
import gov.nasa.worldwind.cache.BasicMemoryCache;
import gov.nasa.worldwind.cache.MemoryCache;
import gov.nasa.worldwind.cache.MemoryCacheSet;
import gov.nasa.worldwind.geom.Angle;
import gov.nasa.worldwind.geom.Cylinder;
import gov.nasa.worldwind.geom.Extent;
import gov.nasa.worldwind.geom.Frustum;
import gov.nasa.worldwind.geom.Intersection;
import gov.nasa.worldwind.geom.LatLon;
import gov.nasa.worldwind.geom.Line;
import gov.nasa.worldwind.geom.Plane;
import gov.nasa.worldwind.geom.Position;
import gov.nasa.worldwind.geom.Sector;
import gov.nasa.worldwind.geom.Triangle;
import gov.nasa.worldwind.geom.Vec4;
import gov.nasa.worldwind.globes.Globe;
import gov.nasa.worldwind.pick.PickSupport;
import gov.nasa.worldwind.pick.PickedObject;
import gov.nasa.worldwind.render.DrawContext;
import gov.nasa.worldwind.render.Material;
import gov.nasa.worldwind.terrain.SectorGeometry;
import gov.nasa.worldwind.terrain.SectorGeometry.BoundaryEdge;
import gov.nasa.worldwind.terrain.SectorGeometry.ExtractedShapeDescription;
import gov.nasa.worldwind.terrain.SectorGeometry.GeographicTextureCoordinateComputer;
import gov.nasa.worldwind.terrain.SectorGeometryList;
import gov.nasa.worldwind.terrain.Tessellator;
import gov.nasa.worldwind.util.Logging;
import java.awt.Color;
import java.awt.Point;
import java.nio.Buffer;
import java.nio.DoubleBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import gov.nasa.worldwind.examples.sunlight.RectangularNormalTessellator.RectTile;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Logger;
import javax.media.opengl.GL;
import java.lang.Object;

//my imports
import gov.nasa.worldwind.cache.GpuResourceCache;
import java.nio.FloatBuffer;
import gov.nasa.worldwind.util.OGLTextRenderer;
import gov.nasa.worldwind.util.OGLStackHandler;
import com.sun.opengl.util.j2d.TextRenderer;
import gov.nasa.worldwind.avlist.AVKey;

public class RectangularNormalTessellator extends WWObjectImpl
  implements Tessellator
{
	// TODO: Make all this configurable
	private static final double DEFAULT_LOG10_RESOLUTION_TARGET = 1.3;
	private static final int DEFAULT_MAX_LEVEL = 12;
	private static final int DEFAULT_NUM_LAT_SUBDIVISIONS = 5;
	private static final int DEFAULT_NUM_LON_SUBDIVISIONS = 10;
	private static final int DEFAULT_DENSITY = 20;
	private static final String CACHE_NAME = "Terrain";
	private static final String CACHE_ID = RectangularNormalTessellator.class.getName();

	// Tri-strip indices and texture coordinates. These depend only on density and can therefore be statically cached.
	private static final HashMap<Integer, DoubleBuffer> parameterizations = new HashMap<Integer, DoubleBuffer>();
	private static final HashMap<Integer, IntBuffer> indexLists = new HashMap<Integer, IntBuffer>();

	private ArrayList<RectTile> topLevels;
	private PickSupport pickSupport = new PickSupport();
	private SectorGeometryList currentTiles = new SectorGeometryList();
	private Frustum currentFrustum;
	private Sector currentCoverage; // union of all tiles selected during call to render()
	private boolean makeTileSkirts = true;
	private int currentLevel;
	private int maxLevel = DEFAULT_MAX_LEVEL;
	private Globe globe;
	private int density = DEFAULT_DENSITY;

    // Lighting
    private Vec4 lightDirection;
    private Material material = new Material(Color.WHITE);
    private Color lightColor = Color.WHITE;
    private Color ambientColor = new Color(.1f, .1f, .1f);
  protected long updateFrequency = 2000; // milliseconds
  
  protected static final HashMap<Integer, Object> textureCoordVboCacheKeys = new HashMap<Integer, Object>();
  protected static final HashMap<Integer, Object> indexListsVboCacheKeys = new HashMap<Integer, Object>();

private static class CacheKey
    {
        private final Sector sector;
        private final int density;
        private final Object globeStateKey;

        public CacheKey(DrawContext dc, Sector sector, int density)
        {
            this.sector = sector;
            this.density = density;
            this.globeStateKey = dc.getGlobe().getStateKey(dc);
        }

        @SuppressWarnings({"EqualsWhichDoesntCheckParameterClass"})
        public boolean equals(Object o)
        {
            if (this == o) return true;

            CacheKey cacheKey = (CacheKey) o; // Note: no check of class type equivalence, for performance

            if (density != cacheKey.density) return false;
            if (globeStateKey != null ? !globeStateKey.equals(cacheKey.globeStateKey) : cacheKey.globeStateKey != null)
                return false;
            //noinspection RedundantIfStatement
            if (sector != null ? !sector.equals(cacheKey.sector) : cacheKey.sector != null) return false;

            return true;
        }

        public int hashCode()
        {
            int result;
            result = (sector != null ? sector.hashCode() : 0);
            result = 31 * result + density;
            result = 31 * result + (globeStateKey != null ? globeStateKey.hashCode() : 0);
            return result;
        }
    }

    public long getUpdateFrequency()
    {
        return this.updateFrequency;
    }

    public void setUpdateFrequency(long updateFrequency)
    {
        this.updateFrequency = updateFrequency;
    }


    public SectorGeometryList tessellate(DrawContext dc)
	{
		if (dc == null)
		{
			String msg = Logging.getMessage("nullValue.DrawContextIsNull");
			Logging.logger().severe(msg);
			throw new IllegalArgumentException(msg);
		}

		if (dc.getView() == null)
		{
			String msg = Logging.getMessage("nullValue.ViewIsNull");
			Logging.logger().severe(msg);
			throw new IllegalStateException(msg);
		}

		if (!WorldWind.getMemoryCacheSet().containsCache(CACHE_ID))
		{
			long size = Configuration.getLongValue(
					AVKey.SECTOR_GEOMETRY_CACHE_SIZE, 20000000L);
			MemoryCache cache = new BasicMemoryCache((long) (0.85 * size), size);
			cache.setName(CACHE_NAME);
			WorldWind.getMemoryCacheSet().addCache(CACHE_ID, cache);
		}

		if (this.topLevels == null)
			this.topLevels = this.createTopLevelTiles(dc);

		this.currentTiles.clear();
		this.currentLevel = 0;
		this.currentCoverage = null;

		this.currentFrustum = dc.getView().getFrustumInModelCoordinates();
		for (RectTile tile : this.topLevels)
		{
			this.selectVisibleTiles(dc, tile);
		}

		this.currentTiles.setSector(this.currentCoverage);

		for (SectorGeometry tile : this.currentTiles)
		{
			this.makeVerts(dc, (RectTile) tile);
		}

		return this.currentTiles;
	}
    
private ArrayList<RectTile> createTopLevelTiles(DrawContext dc)
	{
		ArrayList<RectTile> tops = new ArrayList<RectTile>(
				DEFAULT_NUM_LAT_SUBDIVISIONS * DEFAULT_NUM_LON_SUBDIVISIONS);

		this.globe = dc.getGlobe();
		double deltaLat = 180d / DEFAULT_NUM_LAT_SUBDIVISIONS;
		double deltaLon = 360d / DEFAULT_NUM_LON_SUBDIVISIONS;
		Angle lastLat = Angle.NEG90;

		for (int row = 0; row < DEFAULT_NUM_LAT_SUBDIVISIONS; row++)
		{
			Angle lat = lastLat.addDegrees(deltaLat);
			if (lat.getDegrees() + 1d > 90d)
				lat = Angle.POS90;

			Angle lastLon = Angle.NEG180;

			for (int col = 0; col < DEFAULT_NUM_LON_SUBDIVISIONS; col++)
			{
				Angle lon = lastLon.addDegrees(deltaLon);
				if (lon.getDegrees() + 1d > 180d)
					lon = Angle.POS180;

				Sector tileSector = new Sector(lastLat, lat, lastLon, lon);
				tops.add(this.createTile(dc, tileSector, 0));
				lastLon = lon;
			}
			lastLat = lat;
		}

		return tops;
	}

	private RectTile createTile(DrawContext dc, Sector tileSector, int level)
	{
		Cylinder cylinder = dc.getGlobe().computeBoundingCylinder(
				dc.getVerticalExaggeration(), tileSector);
		double cellSize = tileSector.getDeltaLatRadians()
				* dc.getGlobe().getRadius() / this.density;

		return new RectTile(this, cylinder, level, this.density, tileSector,
				cellSize);
	}

	public boolean isMakeTileSkirts()
	{
		return makeTileSkirts;
	}

	public void setMakeTileSkirts(boolean makeTileSkirts)
	{
		this.makeTileSkirts = makeTileSkirts;
	}

    public Vec4 getLightDirection()
    {
        return this.lightDirection;
    }

    public void setLightDirection(Vec4 direction)
    {
        this.lightDirection = direction;
    }

    public Color getLightColor()
    {
        return this.lightColor;
    }

    public void setLightColor(Color color)
    {
        if (color == null)
        {
            String msg = Logging.getMessage("nullValue.ColorIsNull");
            Logging.logger().severe(msg);
            throw new IllegalArgumentException(msg);
        }
        this.lightColor = color;
    }

    public Color getAmbientColor()
    {
        return this.ambientColor;
    }

    public void setAmbientColor(Color color)
    {
        if (color == null)
        {
            String msg = Logging.getMessage("nullValue.ColorIsNull");
            Logging.logger().severe(msg);
            throw new IllegalArgumentException(msg);
        }
        this.ambientColor = color;
    }

//	public int getTargetResolution(DrawContext dc, RectTile tile)
//	{
//		return dc.getGlobe().getElevationModel().getTargetResolution(dc, tile.sector, tile.density);
//	}

	private void selectVisibleTiles(DrawContext dc, RectTile tile)
	{
		Extent extent = tile.getExtent();
		if (extent != null && !extent.intersects(this.currentFrustum))
			return;

		if (this.currentLevel < this.maxLevel - 1 && this.needToSplit(dc, tile))
		{
			++this.currentLevel;
			RectTile[] subtiles = this.split(dc, tile);
			for (RectTile child : subtiles)
			{
				this.selectVisibleTiles(dc, child);
			}
			--this.currentLevel;
			return;
		}
		this.currentCoverage = tile.getSector().union(this.currentCoverage);
		this.currentTiles.add(tile);
	}

	private boolean needToSplit(DrawContext dc, RectTile tile)
	{
		Vec4[] corners = tile.sector.computeCornerPoints(dc.getGlobe(), dc.getVerticalExaggeration());
		Vec4 centerPoint = tile.sector.computeCenterPoint(dc.getGlobe(), dc.getVerticalExaggeration());

		View view = dc.getView();
		double d1 = view.getEyePoint().distanceTo3(corners[0]);
		double d2 = view.getEyePoint().distanceTo3(corners[1]);
		double d3 = view.getEyePoint().distanceTo3(corners[2]);
		double d4 = view.getEyePoint().distanceTo3(corners[3]);
		double d5 = view.getEyePoint().distanceTo3(centerPoint);

		double minDistance = d1;
		if (d2 < minDistance)
			minDistance = d2;
		if (d3 < minDistance)
			minDistance = d3;
		if (d4 < minDistance)
			minDistance = d4;
		if (d5 < minDistance)
			minDistance = d5;

		double logDist = Math.log10(minDistance);
		boolean useTile = tile.log10CellSize <= (logDist - DEFAULT_LOG10_RESOLUTION_TARGET);

		return !useTile;
	}

	private RectTile[] split(DrawContext dc, RectTile tile)
	{
		Sector[] sectors = tile.sector.subdivide();

		RectTile[] subTiles = new RectTile[4];
		subTiles[0] = this.createTile(dc, sectors[0], tile.level + 1);
		subTiles[1] = this.createTile(dc, sectors[1], tile.level + 1);
		subTiles[2] = this.createTile(dc, sectors[2], tile.level + 1);
		subTiles[3] = this.createTile(dc, sectors[3], tile.level + 1);

		return subTiles;
	}

    private RectangularNormalTessellator.CacheKey createCacheKey(DrawContext dc, RectTile tile)
    {
        return new CacheKey(dc, tile.sector, tile.density);
    }

    private void makeVerts(DrawContext dc, RectTile tile)
    {
        // First see if the vertices have been previously computed and are in the cache. Since the elevation model
        // can change between frames, regenerate and re-cache vertices every second.
        MemoryCache cache = WorldWind.getMemoryCache(CACHE_ID);
        CacheKey cacheKey = this.createCacheKey(dc, tile);
        tile.ri = (RenderInfo) cache.getObject(cacheKey);
        if (tile.ri != null && tile.ri.time >= System.currentTimeMillis() - 1000) // Regenerate cache after one second
            return;

        tile.ri = this.buildVerts(dc, tile, this.makeTileSkirts);
        if (tile.ri != null)
        {
            cacheKey = this.createCacheKey(dc, tile);
            cache.add(cacheKey, tile.ri, tile.ri.getSizeInBytes());
        }
    }

	public RenderInfo buildVerts(DrawContext dc, RectTile tile, boolean makeSkirts)
	{
		int density = tile.density;
		int side = density + 3;
		int numVertices = side * side;
		java.nio.DoubleBuffer verts = BufferUtil.newDoubleBuffer(numVertices * 3);
        ArrayList<LatLon> latlons = this.computeLocations(tile);
        double[] elevations = new double[latlons.size()];
        dc.getGlobe().getElevations(tile.sector, latlons, tile.getResolution(), elevations);

		Globe globe = dc.getGlobe();

		Angle dLat = tile.sector.getDeltaLat().divide(density);
		Angle latMin = tile.sector.getMinLatitude();
		Angle latMax = tile.sector.getMaxLatitude();

		Angle dLon = tile.sector.getDeltaLon().divide(density);
		Angle lonMin = tile.sector.getMinLongitude();
		Angle lonMax = tile.sector.getMaxLongitude();

		Angle lat, lon;
		int iv = 0;
		double elevation, verticalExaggeration = dc.getVerticalExaggeration();
		Vec4 p;

		LatLon centroid = tile.sector.getCentroid();
		Vec4 refCenter = globe.computePointFromPosition(centroid.getLatitude(), centroid.getLongitude(), 0d);

        int ie = 0;
        Iterator<LatLon> latLonIter = latlons.iterator();

        // Compute verts without skirts
		for (int j = 0; j < side; j++)
		{
			for (int i = 0; i < side; i++)
			{
                LatLon latlon = latLonIter.next();
                elevation = verticalExaggeration * elevations[ie++];
				p = globe.computePointFromPosition(latlon.getLatitude(), latlon.getLongitude(), elevation);
				verts.put(iv++, p.x - refCenter.x).put(iv++, p.y - refCenter.y).put(iv++, p.z - refCenter.z);
			}
		}

		// Compute indices and normals
		java.nio.IntBuffer indices = getIndices(density);
		java.nio.DoubleBuffer norms = getNormals(density, verts, indices, refCenter);

		// Fold down the sides as skirts
		double exaggeratedMinElevation = makeSkirts ? Math.abs(globe.getMinElevation() * verticalExaggeration) : 0;
		lat = latMin;
		for (int j = 0; j < side; j++)
		{
			//min longitude side
            ie = j * side + 1;
            elevation = verticalExaggeration * elevations[ie];
			elevation -= exaggeratedMinElevation >= 0 ? exaggeratedMinElevation : -exaggeratedMinElevation;
			p = globe.computePointFromPosition(lat, lonMin, elevation);
			iv = (j * side) * 3;
			verts.put(iv++, p.x - refCenter.x).put(iv++, p.y - refCenter.y)
					.put(iv++, p.z - refCenter.z);

			//max longitude side
            ie += side - 2;
            elevation = verticalExaggeration * elevations[ie];
			elevation -= exaggeratedMinElevation >= 0 ? exaggeratedMinElevation : -exaggeratedMinElevation;
			p = globe.computePointFromPosition(lat, lonMax, elevation);
			iv = ((j + 1) * side - 1) * 3;
			verts.put(iv++, p.x - refCenter.x).put(iv++, p.y - refCenter.y)
					.put(iv++, p.z - refCenter.z);

			if (j > density)
				lat = latMax;
			else if (j != 0)
				lat = lat.add(dLat);
		}

		lon = lonMin;
		for (int i = 0; i < side; i++)
		{
			//min latitude side
            ie = i + side;
            elevation = verticalExaggeration * elevations[ie];
			elevation -= exaggeratedMinElevation >= 0 ? exaggeratedMinElevation : -exaggeratedMinElevation;
			p = globe.computePointFromPosition(latMin, lon, elevation);
			iv = i * 3;
			verts.put(iv++, p.x - refCenter.x).put(iv++, p.y - refCenter.y)
					.put(iv++, p.z - refCenter.z);

			//max latitude side
            ie += (side - 2) * side;
            elevation = verticalExaggeration * elevations[ie];
			elevation -= exaggeratedMinElevation >= 0 ? exaggeratedMinElevation : -exaggeratedMinElevation;
			p = globe.computePointFromPosition(latMax, lon, elevation);
			iv = (side * (side - 1) + i) * 3;
			verts.put(iv++, p.x - refCenter.x).put(iv++, p.y - refCenter.y)
					.put(iv++, p.z - refCenter.z);

			if (i > density)
				lon = lonMax;
			else if (i != 0)
				lon = lon.add(dLon);
		}

		return new RenderInfo(density, verts, getTextureCoordinates(density), norms, refCenter);
	}

    private ArrayList<LatLon> computeLocations(RectTile tile)
    {
        int density = tile.density;
        int numVertices = (density + 3) * (density + 3);

        Angle latMax = tile.sector.getMaxLatitude();
        Angle dLat = tile.sector.getDeltaLat().divide(density);
        Angle lat = tile.sector.getMinLatitude().subtract(dLat);

        Angle lonMin = tile.sector.getMinLongitude();
        Angle lonMax = tile.sector.getMaxLongitude();
        Angle dLon = tile.sector.getDeltaLon().divide(density);

        ArrayList<LatLon> latlons = new ArrayList<LatLon>(numVertices);
        for (int j = 0; j <= density + 2; j++)
        {
            Angle lon = lonMin.subtract(dLon);
            for (int i = 0; i <= density + 2; i++)
            {
                latlons.add(new LatLon(lat, lon));

                lon = lon.add(dLon);

                if (lon.degrees < -180)
                    lon = Angle.NEG180;
                else if (lon.degrees > 180)
                    lon = Angle.POS180;
            }

            lat = lat.add(dLat);
        }

        return latlons;
    }

    protected void renderMultiTexture(DrawContext dc, RectTile tile, int numTextureUnits)
    {
        if (dc == null)
        {
            String msg = Logging.getMessage("nullValue.DrawContextIsNull");
            Logging.logger().severe(msg);
            throw new IllegalArgumentException(msg);
        }

        if (numTextureUnits < 1)
        {
            String msg = Logging.getMessage("generic.NumTextureUnitsLessThanOne");
            Logging.logger().severe(msg);
            throw new IllegalArgumentException(msg);
        }

        this.render(dc, tile, numTextureUnits);
    }

    protected void render(DrawContext dc, RectTile tile)
    {
        if (dc == null)
        {
            String msg = Logging.getMessage("nullValue.DrawContextIsNull");
            Logging.logger().severe(msg);
            throw new IllegalArgumentException(msg);
        }

        this.render(dc, tile, 1);
    }

	    protected long render(DrawContext dc, RectTile tile, int numTextureUnits)
    {
        if (tile.ri == null)
        {
            String msg = Logging.getMessage("nullValue.RenderInfoIsNull");
            Logging.logger().severe(msg);
            throw new IllegalStateException(msg);
        }

        if (dc.getGLRuntimeCapabilities().isUseVertexBufferObject())
        {
            if (!this.renderVBO(dc, tile, numTextureUnits))
            {
                // Fall back to VA rendering. This is an error condition at this point because something went wrong with
                // VBO fill or binding. But we can still probably draw the tile using vertex arrays.
                dc.getGL().glBindBuffer(GL.GL_ARRAY_BUFFER, 0);
                dc.getGL().glBindBuffer(GL.GL_ELEMENT_ARRAY_BUFFER, 0);
                this.renderVA(dc, tile, numTextureUnits);
            }
        }
        else
        {
            this.renderVA(dc, tile, numTextureUnits);
        }

        return tile.ri.indices.limit() - 2; // return number of triangles rendered
    }

    protected void renderVA(DrawContext dc, RectTile tile, int numTextureUnits)
    {
        GL gl = dc.getGL();

        gl.glVertexPointer(3, GL.GL_FLOAT, 0, tile.ri.vertices.rewind());

        for (int i = 0; i < numTextureUnits; i++)
        {
            gl.glClientActiveTexture(GL.GL_TEXTURE0 + i);
            gl.glEnableClientState(GL.GL_TEXTURE_COORD_ARRAY);
            Object texCoords = dc.getValue(AVKey.TEXTURE_COORDINATES);
            if (texCoords != null && texCoords instanceof DoubleBuffer)
                gl.glTexCoordPointer(2, GL.GL_FLOAT, 0, ((DoubleBuffer) texCoords).rewind());
            else
                gl.glTexCoordPointer(2, GL.GL_FLOAT, 0, tile.ri.texCoords.rewind());
        }

        gl.glDrawElements(javax.media.opengl.GL.GL_TRIANGLE_STRIP, tile.ri.indices.limit(),
            javax.media.opengl.GL.GL_UNSIGNED_INT, tile.ri.indices.rewind());
    }

    protected boolean renderVBO(DrawContext dc, RectTile tile, int numTextureUnits)
    {
        if (tile.ri.isVboBound || this.bindVbos(dc, tile, numTextureUnits))
        {
            // Render the tile
            dc.getGL().glDrawElements(javax.media.opengl.GL.GL_TRIANGLE_STRIP, tile.ri.indices.limit(),
                javax.media.opengl.GL.GL_UNSIGNED_INT, 0);
            return true;
        }
        else
        {
            return false;
        }
    }

    private void beginLighting(DrawContext dc)
    {
        GL gl = dc.getGL();
        gl.glPushAttrib(GL.GL_ENABLE_BIT | GL.GL_CURRENT_BIT | GL.GL_LIGHTING_BIT);

        this.material.apply(gl, GL.GL_FRONT);
        gl.glDisable(GL.GL_COLOR_MATERIAL);

        float[] lightPosition = {(float)-lightDirection.x, (float)-lightDirection.y, (float)-lightDirection.z, 0.0f};
        float[] lightDiffuse = new float[4];
        float[] lightAmbient = new float[4];
        lightColor.getRGBComponents(lightDiffuse);
        ambientColor.getRGBComponents(lightAmbient);

        gl.glLightfv(GL.GL_LIGHT1, GL.GL_POSITION, lightPosition, 0);
        gl.glLightfv(GL.GL_LIGHT1, GL.GL_DIFFUSE, lightDiffuse, 0);
        gl.glLightfv(GL.GL_LIGHT1, GL.GL_AMBIENT, lightAmbient, 0);

        gl.glDisable(GL.GL_LIGHT0);
        gl.glEnable(GL.GL_LIGHT1);
        gl.glEnable(GL.GL_LIGHTING);
    }

    private void endLighting(DrawContext dc)
    {
        GL gl = dc.getGL();

        gl.glDisable(GL.GL_LIGHT1);
        gl.glEnable(GL.GL_LIGHT0);
        gl.glDisable(GL.GL_LIGHTING);

        gl.glPopAttrib();
    }

        protected void renderWireframe(DrawContext dc, RectTile tile, boolean showTriangles, boolean showTileBoundary)
    {
        if (dc == null)
        {
            String msg = Logging.getMessage("nullValue.DrawContextIsNull");
            Logging.logger().severe(msg);
            throw new IllegalArgumentException(msg);
        }

        if (tile.ri == null)
        {
            String msg = Logging.getMessage("nullValue.RenderInfoIsNull");
            Logging.logger().severe(msg);
            throw new IllegalStateException(msg);
        }

        dc.getView().pushReferenceCenter(dc, tile.ri.referenceCenter);

        javax.media.opengl.GL gl = dc.getGL();
        gl.glPushAttrib(
            GL.GL_DEPTH_BUFFER_BIT | GL.GL_POLYGON_BIT | GL.GL_ENABLE_BIT | GL.GL_CURRENT_BIT);
        gl.glEnable(GL.GL_BLEND);
        gl.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE);
        gl.glDisable(javax.media.opengl.GL.GL_DEPTH_TEST);
        gl.glEnable(javax.media.opengl.GL.GL_CULL_FACE);
        gl.glCullFace(javax.media.opengl.GL.GL_BACK);
        gl.glColor4d(1d, 1d, 1d, 0.2);
        gl.glPolygonMode(javax.media.opengl.GL.GL_FRONT, javax.media.opengl.GL.GL_LINE);

        if (showTriangles)
        {
            OGLStackHandler ogsh = new OGLStackHandler();

            try
            {
                ogsh.pushClientAttrib(gl, GL.GL_CLIENT_VERTEX_ARRAY_BIT);

                gl.glEnableClientState(GL.GL_VERTEX_ARRAY);

                gl.glVertexPointer(3, GL.GL_FLOAT, 0, tile.ri.vertices.rewind());
                gl.glDrawElements(javax.media.opengl.GL.GL_TRIANGLE_STRIP, tile.ri.indices.limit(),
                    javax.media.opengl.GL.GL_UNSIGNED_INT, tile.ri.indices.rewind());
            }
            finally
            {
                ogsh.pop(gl);
            }
        }

        dc.getView().popReferenceCenter(dc);

        gl.glPopAttrib();

        if (showTileBoundary)
            this.renderPatchBoundary(dc, tile);
    }
        
         protected void renderPatchBoundary(DrawContext dc, RectTile tile)
    {
        GL gl = dc.getGL();
        OGLStackHandler ogsh = new OGLStackHandler();

        ogsh.pushAttrib(gl, GL.GL_ENABLE_BIT | GL.GL_CURRENT_BIT | GL.GL_POLYGON_BIT);
        try
        {
            gl.glDisable(GL.GL_BLEND);

            // Don't perform depth clipping but turn on backface culling
            gl.glDisable(GL.GL_DEPTH_TEST);
            gl.glEnable(GL.GL_CULL_FACE);
            gl.glCullFace(GL.GL_BACK);
            gl.glPolygonMode(GL.GL_FRONT, GL.GL_LINE);

            Vec4[] corners = tile.sector.computeCornerPoints(dc.getGlobe(), dc.getVerticalExaggeration());

            gl.glColor4d(1d, 0, 0, 1d);
            gl.glBegin(javax.media.opengl.GL.GL_QUADS);
            gl.glVertex3d(corners[0].x, corners[0].y, corners[0].z);
            gl.glVertex3d(corners[1].x, corners[1].y, corners[1].z);
            gl.glVertex3d(corners[2].x, corners[2].y, corners[2].z);
            gl.glVertex3d(corners[3].x, corners[3].y, corners[3].z);
            gl.glEnd();
        }
        finally
        {
            ogsh.pop(gl);
        }
    }
        
	private void renderPatchBoundary(DrawContext dc, RectTile tile, GL gl)
	{
		// TODO: Currently only works if called from renderWireframe because no state is set here.
		// TODO: Draw the boundary using the vertices along the boundary rather than just at the corners.
		gl.glColor4d(1d, 0, 0, 1d);
		Vec4[] corners = tile.sector.computeCornerPoints(dc.getGlobe(), dc.getVerticalExaggeration());

		gl.glBegin(javax.media.opengl.GL.GL_QUADS);
		gl.glVertex3d(corners[0].x, corners[0].y, corners[0].z);
		gl.glVertex3d(corners[1].x, corners[1].y, corners[1].z);
		gl.glVertex3d(corners[2].x, corners[2].y, corners[2].z);
		gl.glVertex3d(corners[3].x, corners[3].y, corners[3].z);
		gl.glEnd();
	}

	private void renderBoundingVolume(DrawContext dc, RectTile tile)
	{
		Extent extent = tile.getExtent();
		if (extent == null)
			return;

		if (extent instanceof Cylinder)
			((Cylinder) extent).render(dc);
	}

	private PickedObject[] pick(DrawContext dc, RectTile tile,
			List<? extends Point> pickPoints)
	{
		if (dc == null)
		{
			String msg = Logging.getMessage("nullValue.DrawContextIsNull");
			Logging.logger().severe(msg);
			throw new IllegalArgumentException(msg);
		}

		if (pickPoints.size() == 0)
			return null;

		if (tile.ri == null)
			return null;

		PickedObject[] pos = new PickedObject[pickPoints.size()];
		this.renderTrianglesWithUniqueColors(dc, tile);
		for (int i = 0; i < pickPoints.size(); i++)
		{
			pos[i] = this.resolvePick(dc, tile, pickPoints.get(i));
		}

		return pos;
	}

	private void pick(DrawContext dc, RectTile tile, Point pickPoint)
	{
		if (dc == null)
		{
			String msg = Logging.getMessage("nullValue.DrawContextIsNull");
			Logging.logger().severe(msg);
			throw new IllegalArgumentException(msg);
		}

		if (tile.ri == null)
			return;

		renderTrianglesWithUniqueColors(dc, tile);
		PickedObject po = this.resolvePick(dc, tile, pickPoint);
		if (po != null)
			dc.addPickedObject(po);
	}

	private void renderTrianglesWithUniqueColors(DrawContext dc, RectTile tile)
	{
		if (dc == null)
		{
			String message = Logging.getMessage("nullValue.DrawContextIsNull");
			Logging.logger().severe(message);
			throw new IllegalStateException(message);
		}

		if (tile.ri.vertices == null)
			return;

		tile.ri.vertices.rewind();
		tile.ri.indices.rewind();

		javax.media.opengl.GL gl = dc.getGL();

		if (null != tile.ri.referenceCenter)
			dc.getView().pushReferenceCenter(dc, tile.ri.referenceCenter);

		tile.minColorCode = dc.getUniquePickColor().getRGB();
		int trianglesNum = tile.ri.indices.capacity() - 2;

		gl.glBegin(GL.GL_TRIANGLES);
		for (int i = 0; i < trianglesNum; i++)
		{
			java.awt.Color color = dc.getUniquePickColor();
			gl.glColor3ub((byte) (color.getRed() & 0xFF), (byte) (color
					.getGreen() & 0xFF), (byte) (color.getBlue() & 0xFF));

			int vIndex = 3 * tile.ri.indices.get(i);
			gl.glVertex3d(tile.ri.vertices.get(vIndex), tile.ri.vertices
					.get(vIndex + 1), tile.ri.vertices.get(vIndex + 2));

			vIndex = 3 * tile.ri.indices.get(i + 1);
			gl.glVertex3d(tile.ri.vertices.get(vIndex), tile.ri.vertices
					.get(vIndex + 1), tile.ri.vertices.get(vIndex + 2));

			vIndex = 3 * tile.ri.indices.get(i + 2);
			gl.glVertex3d(tile.ri.vertices.get(vIndex), tile.ri.vertices
					.get(vIndex + 1), tile.ri.vertices.get(vIndex + 2));
		}
		gl.glEnd();
		tile.maxColorCode = dc.getUniquePickColor().getRGB();

		if (null != tile.ri.referenceCenter)
			dc.getView().popReferenceCenter(dc);
	}

	private PickedObject resolvePick(DrawContext dc, RectTile tile,
			Point pickPoint)
	{
		int colorCode = this.pickSupport.getTopColor(dc, pickPoint);
		if (colorCode < tile.minColorCode || colorCode > tile.maxColorCode)
			return null;

		double EPSILON = (double) 0.00001f;

		int triangleIndex = colorCode - tile.minColorCode - 1;

		if (tile.ri.indices == null
				|| triangleIndex >= (tile.ri.indices.capacity() - 2))
			return null;

		double centerX = tile.ri.referenceCenter.x;
		double centerY = tile.ri.referenceCenter.y;
		double centerZ = tile.ri.referenceCenter.z;

		int vIndex = 3 * tile.ri.indices.get(triangleIndex);
		Vec4 v0 = new Vec4((tile.ri.vertices.get(vIndex++) + centerX),
				(tile.ri.vertices.get(vIndex++) + centerY), (tile.ri.vertices
						.get(vIndex) + centerZ));

		vIndex = 3 * tile.ri.indices.get(triangleIndex + 1);
		Vec4 v1 = new Vec4((tile.ri.vertices.get(vIndex++) + centerX),
				(tile.ri.vertices.get(vIndex++) + centerY), (tile.ri.vertices
						.get(vIndex) + centerZ));

		vIndex = 3 * tile.ri.indices.get(triangleIndex + 2);
		Vec4 v2 = new Vec4((tile.ri.vertices.get(vIndex++) + centerX),
				(tile.ri.vertices.get(vIndex++) + centerY), (tile.ri.vertices
						.get(vIndex) + centerZ));

		// get triangle edge vectors and plane normal
		Vec4 e1 = v1.subtract3(v0);
		Vec4 e2 = v2.subtract3(v0);
		Vec4 N = e1.cross3(e2); // if N is 0, the triangle is degenerate, we are not dealing with it

		Line ray = dc.getView().computeRayFromScreenPoint(pickPoint.getX(),
				pickPoint.getY());

		Vec4 w0 = ray.getOrigin().subtract3(v0);
		double a = -N.dot3(w0);
		double b = N.dot3(ray.getDirection());
		if (java.lang.Math.abs(b) < EPSILON) // ray is parallel to triangle plane
			return null; // if a == 0 , ray lies in triangle plane
		double r = a / b;

		Vec4 intersect = ray.getOrigin().add3(ray.getDirection().multiply3(r));
		Position pp = dc.getGlobe().computePositionFromPoint(intersect);

		// Draw the elevation from the elevation model, not the geode.
		double elev = dc.getGlobe().getElevation(pp.getLatitude(),
				pp.getLongitude());
		Position p = new Position(pp.getLatitude(), pp.getLongitude(), elev);

		return new PickedObject(pickPoint, colorCode, p, pp.getLatitude(), pp
				.getLongitude(), elev, true);
	}


     /**
     * Determines if and where a ray intersects a <code>RectTile</code> geometry.
     *
     * @param tile the <Code>RectTile</code> which geometry is to be tested for intersection.
     * @param line the ray for which an intersection is to be found.
     * @return an array of <code>Intersection</code> sorted by increasing distance from the line origin, or null if
     * no intersection was found.
     */
    private Intersection[] intersect(RectTile tile, Line line)
    {
        if (line == null)
        {
            String msg = Logging.getMessage("nullValue.LineIsNull");
            Logging.logger().severe(msg);
            throw new IllegalArgumentException(msg);
        }

        if (tile.ri.vertices == null)
            return null;

        // Compute 'vertical' plane perpendicular to the ground, that contains the ray
        Vec4 normalV = line.getDirection().cross3(globe.computeSurfaceNormalAtPoint(line.getOrigin()));
        Plane verticalPlane = new Plane(normalV.x(),  normalV.y(),  normalV.z(),  -line.getOrigin().dot3(normalV));
        if (!tile.getExtent().intersects(verticalPlane))
            return null;

        // Compute 'horizontal' plane perpendicular to the vertical plane, that contains the ray
        Vec4 normalH = line.getDirection().cross3(normalV);
        Plane horizontalPlane = new Plane(normalH.x(),  normalH.y(),  normalH.z(),  -line.getOrigin().dot3(normalH));
        if (!tile.getExtent().intersects(horizontalPlane))
            return null;

        Intersection[] hits;
        ArrayList<Intersection> list = new ArrayList<Intersection>();

        int[] indices = new int[tile.ri.indices.limit()];
        double[] coords = new double[tile.ri.vertices.limit()];
        tile.ri.indices.rewind();
        tile.ri.vertices.rewind();
        tile.ri.indices.get(indices, 0, indices.length);
        tile.ri.vertices.get(coords, 0, coords.length);
        tile.ri.indices.rewind();
        tile.ri.vertices.rewind();

        int trianglesNum = tile.ri.indices.capacity() - 2;
        double centerX = tile.ri.referenceCenter.x;
        double centerY = tile.ri.referenceCenter.y;
        double centerZ = tile.ri.referenceCenter.z;

        // Compute maximum cell size based on tile delta lat, density and globe radius
        double cellSide = tile.getSector().getDeltaLatRadians() * globe.getRadius() / density;
        double maxCellRadius = Math.sqrt(cellSide * cellSide * 2) / 2;   // half cell diagonal

        // Compute maximum elevation difference - assume cylinder as Extent
        double elevationSpan = ((Cylinder)tile.getExtent()).getCylinderHeight();

        // TODO: ignore back facing triangles?
        // Loop through all tile cells - triangle pairs
        int startIndice = (density + 2) * 2 + 6; // skip firts skirt row and a couple degenerate cells
        int endIndice = trianglesNum - startIndice; // ignore last skirt row and a couple degenerate cells
        int k = -1;
        for (int i = startIndice; i < endIndice; i += 2)
        {
            // Skip skirts and degenerate triangle cells - based on indice sequence.
            k = k == density - 1 ? -4 : k + 1; // density x terrain cells interleaved with 4 skirt and degenerate cells.
            if (k < 0)
                continue;

            // Triangle pair diagonal - v1 & v2
            int vIndex = 3 * indices[i + 1];
            Vec4 v1 = new Vec4(
                    coords[vIndex++] + centerX,
                    coords[vIndex++] + centerY,
                    coords[vIndex] + centerZ);

            vIndex = 3 * indices[i + 2];
            Vec4 v2 = new Vec4(
                    coords[vIndex++] + centerX,
                    coords[vIndex++] + centerY,
                    coords[vIndex] + centerZ);

            Vec4 cellCenter = Vec4.mix3(.5, v1, v2);

            // Test cell center distance to vertical plane
            if (Math.abs(verticalPlane.distanceTo(cellCenter)) > maxCellRadius)
                continue;

            // Test cell center distance to horizontal plane
            if (Math.abs(horizontalPlane.distanceTo(cellCenter)) > elevationSpan)
                continue;

            // Prepare to test triangles - get other two vertices v0 & v3
            Vec4 p;
            vIndex = 3 * indices[i];
            Vec4 v0 = new Vec4(
                    coords[vIndex++] + centerX,
                    coords[vIndex++] + centerY,
                    coords[vIndex] + centerZ);

            vIndex = 3 * indices[i + 3];
            Vec4 v3 = new Vec4(
                    coords[vIndex++] + centerX,
                    coords[vIndex++] + centerY,
                    coords[vIndex] + centerZ);

            // Test triangle 1 intersection w ray
            Triangle t = new Triangle(v0, v1, v2);
            if ((p = t.intersect(line)) != null)
            {
                list.add(new Intersection(p, false));
            }

            // Test triangle 2 intersection w ray
            t = new Triangle(v1, v2, v3);
            if ((p = t.intersect(line)) != null)
            {
                list.add(new Intersection(p, false));
            }
        }

        int numHits = list.size();
        if (numHits == 0)
            return null;

        hits = new Intersection[numHits];
        list.toArray(hits);

        final Vec4 origin = line.getOrigin();
        Arrays.sort(hits, new Comparator<Intersection>()
        {
            public int compare(Intersection i1, Intersection i2)
            {
                if (i1 == null && i2 == null)
                    return 0;
                if (i2 == null)
                    return -1;
                if (i1 == null)
                    return 1;

                Vec4 v1 = i1.getIntersectionPoint();
                Vec4 v2 = i2.getIntersectionPoint();
                double d1 = origin.distanceTo3(v1);
                double d2 = origin.distanceTo3(v2);
                return Double.compare(d1, d2);
            }
        });

        return hits;
    }

   /**
     * Determines if and where a <code>RectTile</code> geometry intersects the globe ellipsoid at a given elevation.
     * The returned array of <code>Intersection</code> describes a list of individual segments - two
     * <code>Intersection</code> for each, corresponding to each geometry triangle that intersects the given elevation.
     *
     * @param tile the <Code>RectTile</code> which geometry is to be tested for intersection.
     * @param elevation the elevation for which intersection points are to be found.
     * @return an array of <code>Intersection</code> pairs or null if no intersection was found.
     */
    private Intersection[] intersect(RectTile tile, double elevation)
    {
        if (tile.ri.vertices == null)
            return null;

        // Check whether the tile includes the intersection elevation - assume cylinder as Extent
        Cylinder cylinder = ((Cylinder)tile.getExtent());
        if (!(globe.isPointAboveElevation(cylinder.getBottomCenter(), elevation)
            ^ globe.isPointAboveElevation(cylinder.getTopCenter(), elevation)))
            return null;

        Intersection[] hits;
        ArrayList<Intersection> list = new ArrayList<Intersection>();

        int[] indices = new int[tile.ri.indices.limit()];
        double[] coords = new double[tile.ri.vertices.limit()];
        tile.ri.indices.rewind();
        tile.ri.vertices.rewind();
        tile.ri.indices.get(indices, 0, indices.length);
        tile.ri.vertices.get(coords, 0, coords.length);
        tile.ri.indices.rewind();
        tile.ri.vertices.rewind();

        int trianglesNum = tile.ri.indices.capacity() - 2;
        double centerX = tile.ri.referenceCenter.x;
        double centerY = tile.ri.referenceCenter.y;
        double centerZ = tile.ri.referenceCenter.z;

        // Loop through all tile cells - triangle pairs
        int startIndice = (density + 2) * 2 + 6; // skip firts skirt row and a couple degenerate cells
        int endIndice = trianglesNum - startIndice; // ignore last skirt row and a couple degenerate cells
        int k = -1;
        for (int i = startIndice; i < endIndice; i += 2)
        {
            // Skip skirts and degenerate triangle cells - based on indice sequence.
            k = k == density - 1 ? -4 : k + 1; // density x terrain cells interleaved with 4 skirt and degenerate cells.
            if (k < 0)
                continue;

            // Get the four cell corners
            int vIndex = 3 * indices[i];
            Vec4 v0 = new Vec4(
                    coords[vIndex++] + centerX,
                    coords[vIndex++] + centerY,
                    coords[vIndex] + centerZ);

            vIndex = 3 * indices[i + 1];
            Vec4 v1 = new Vec4(
                    coords[vIndex++] + centerX,
                    coords[vIndex++] + centerY,
                    coords[vIndex] + centerZ);

            vIndex = 3 * indices[i + 2];
            Vec4 v2 = new Vec4(
                    coords[vIndex++] + centerX,
                    coords[vIndex++] + centerY,
                    coords[vIndex] + centerZ);

            vIndex = 3 * indices[i + 3];
            Vec4 v3 = new Vec4(
                    coords[vIndex++] + centerX,
                    coords[vIndex++] + centerY,
                    coords[vIndex] + centerZ);

            Intersection[] inter;
            // Test triangle 1 intersection
            if ((inter = globe.intersect(new Triangle(v0, v1, v2), elevation)) != null)
            {
                list.add(inter[0]);
                list.add(inter[1]);
            }

            // Test triangle 2 intersection
            if ((inter = globe.intersect(new Triangle(v1, v2, v3), elevation)) != null)
            {
                list.add(inter[0]);
                list.add(inter[1]);
            }
        }

        int numHits = list.size();
        if (numHits == 0)
            return null;

        hits = new Intersection[numHits];
        list.toArray(hits);

        return hits;
    }

    private Vec4 getSurfacePoint(RectTile tile, Angle latitude,
			Angle longitude, double metersOffset)
	{
		Vec4 result = this.getSurfacePoint(tile, latitude, longitude);
		if (metersOffset != 0 && result != null)
			result = applyOffset(this.globe, result, metersOffset);

		return result;
	}


    /**
	 * Offsets <code>point</code> by <code>metersOffset</code> meters.
	 *
	 * @param globe
	 *            the <code>Globe</code> from which to offset
	 * @param point
	 *            the <code>Vec4</code> to offset
	 * @param metersOffset
	 *            the magnitude of the offset
	 * @return <code>point</code> offset along its surface normal as if it were
	 *         on <code>globe</code>
	 */
	private static Vec4 applyOffset(Globe globe, Vec4 point, double metersOffset)
	{
		Vec4 normal = globe.computeSurfaceNormalAtPoint(point);
		point = Vec4.fromLine3(point, metersOffset, normal);
		return point;
	}

	private Vec4 getSurfacePoint(RectTile tile, Angle latitude, Angle longitude)
	{
		if (latitude == null || longitude == null)
		{
			String msg = Logging.getMessage("nullValue.LatLonIsNull");
			Logging.logger().severe(msg);
			throw new IllegalArgumentException(msg);
		}

		if (!tile.sector.contains(latitude, longitude))
		{
			// not on this geometry
			return null;
		}

		if (tile.ri == null)
			return null;

		double lat = latitude.getDegrees();
		double lon = longitude.getDegrees();

		double bottom = tile.sector.getMinLatitude().getDegrees();
		double top = tile.sector.getMaxLatitude().getDegrees();
		double left = tile.sector.getMinLongitude().getDegrees();
		double right = tile.sector.getMaxLongitude().getDegrees();

		double leftDecimal = (lon - left) / (right - left);
		double bottomDecimal = (lat - bottom) / (top - bottom);

		int row = (int) (bottomDecimal * (tile.density));
		int column = (int) (leftDecimal * (tile.density));

		double l = createPosition(column, leftDecimal, tile.ri.density);
		double h = createPosition(row, bottomDecimal, tile.ri.density);

		Vec4 result = interpolate(row, column, l, h, tile.ri);
		result = result.add3(tile.ri.referenceCenter);

		return result;
	}

	/**
	 * Computes from a column (or row) number, and a given offset ranged [0,1]
	 * corresponding to the distance along the edge of this sector, where
	 * between this column and the next column the corresponding position will
	 * fall, in the range [0,1].
	 *
	 * @param start
	 *            the number of the column or row to the left, below or on this
	 *            position
	 * @param decimal
	 *            the distance from the left or bottom of the current sector
	 *            that this position falls
	 * @param density
	 *            the number of intervals along the sector's side
	 * @return a decimal ranged [0,1] representing the position between two
	 *         columns or rows, rather than between two edges of the sector
	 */
	private static double createPosition(int start, double decimal, int density)
	{
		double l = ((double) start) / (double) density;
		double r = ((double) (start + 1)) / (double) density;

		return (decimal - l) / (r - l);
	}

	/**
	 * Calculates a <code>Point</code> that sits at <code>xDec</code> offset
	 * from <code>column</code> to <code>column +
	 * 1</code> and at <code>yDec</code> offset from <code>row</code> to
	 * <code>row + 1</code>. Accounts for the diagonals.
	 *
	 * @param row
	 *            represents the row which corresponds to a <code>yDec</code>
	 *            value of 0
	 * @param column
	 *            represents the column which corresponds to an
	 *            <code>xDec</code> value of 0
	 * @param xDec
	 *            constrained to [0,1]
	 * @param yDec
	 *            constrained to [0,1]
	 * @param ri
	 *            the render info holding the vertices, etc.
	 * @return a <code>Point</code> geometrically within or on the boundary of
	 *         the quadrilateral whose bottom left corner is indexed by (
	 *         <code>row</code>, <code>column</code>)
	 */
	private static Vec4 interpolate(int row, int column, double xDec,
			double yDec, RenderInfo ri)
	{
		row++;
		column++;

		int numVerticesPerEdge = ri.density + 3;

		int bottomLeft = row * numVerticesPerEdge + column;

		bottomLeft *= 3;

		int numVertsTimesThree = numVerticesPerEdge * 3;

		Vec4 bL = new Vec4(ri.vertices.get(bottomLeft), ri.vertices
				.get(bottomLeft + 1), ri.vertices.get(bottomLeft + 2));
		Vec4 bR = new Vec4(ri.vertices.get(bottomLeft + 3), ri.vertices
				.get(bottomLeft + 4), ri.vertices.get(bottomLeft + 5));

		bottomLeft += numVertsTimesThree;

		Vec4 tL = new Vec4(ri.vertices.get(bottomLeft), ri.vertices
				.get(bottomLeft + 1), ri.vertices.get(bottomLeft + 2));
		Vec4 tR = new Vec4(ri.vertices.get(bottomLeft + 3), ri.vertices
				.get(bottomLeft + 4), ri.vertices.get(bottomLeft + 5));

		return interpolate(bL, bR, tR, tL, xDec, yDec);
	}

	/**
	 * Calculates the point at (xDec, yDec) in the two triangles defined by {bL,
	 * bR, tL} and {bR, tR, tL}. If thought of as a quadrilateral, the diagonal
	 * runs from tL to bR. Of course, this isn't a quad, it's two triangles.
	 *
	 * @param bL
	 *            the bottom left corner
	 * @param bR
	 *            the bottom right corner
	 * @param tR
	 *            the top right corner
	 * @param tL
	 *            the top left corner
	 * @param xDec
	 *            how far along, [0,1] 0 = left edge, 1 = right edge
	 * @param yDec
	 *            how far along, [0,1] 0 = bottom edge, 1 = top edge
	 * @return the point xDec, yDec in the co-ordinate system defined by bL, bR,
	 *         tR, tL
	 */
	private static Vec4 interpolate(Vec4 bL, Vec4 bR, Vec4 tR, Vec4 tL,
			double xDec, double yDec)
	{
		double pos = xDec + yDec;
		if (pos == 1)
		{
			// on the diagonal - what's more, we don't need to do any "oneMinusT" calculation
			return new Vec4(tL.x * yDec + bR.x * xDec, tL.y * yDec + bR.y
					* xDec, tL.z * yDec + bR.z * xDec);
		}
		else if (pos > 1)
		{
			// in the "top right" half

			// vectors pointing from top right towards the point we want (can be thought of as "negative" vectors)
			Vec4 horizontalVector = (tL.subtract3(tR)).multiply3(1 - xDec);
			Vec4 verticalVector = (bR.subtract3(tR)).multiply3(1 - yDec);

			return tR.add3(horizontalVector).add3(verticalVector);
		}
		else
		{
			// pos < 1 - in the "bottom left" half

			// vectors pointing from the bottom left towards the point we want
			Vec4 horizontalVector = (bR.subtract3(bL)).multiply3(xDec);
			Vec4 verticalVector = (tL.subtract3(bL)).multiply3(yDec);

			return bL.add3(horizontalVector).add3(verticalVector);
		}
	}

    private static double[] baryCentricCoordsRequireInside(Vec4 pnt, Vec4[] V)
    {
        // if pnt is in the interior of the triangle determined by V, return its
        // barycentric coordinates with respect to V. Otherwise return null.

        // b0:
        final double tol = 1.0e-4;
        double[] b0b1b2 = new double[3];
        double triangleHeight  =
            distanceFromLine(V[0],V[1],V[2].subtract3(V[1]));
        double heightFromPoint =
            distanceFromLine(pnt,V[1],V[2].subtract3(V[1]));
        b0b1b2[0] = heightFromPoint/triangleHeight;
        if (Math.abs(b0b1b2[0]) < tol) b0b1b2[0] = 0.0;
        else if (Math.abs(1.0-b0b1b2[0]) < tol) b0b1b2[0] = 1.0;
        if (b0b1b2[0] < 0.0 || b0b1b2[0] > 1.0)
            return null;

        // b1:
        triangleHeight  = distanceFromLine(V[1],V[0],V[2].subtract3(V[0]));
        heightFromPoint = distanceFromLine(pnt,V[0],V[2].subtract3(V[0]));
        b0b1b2[1] = heightFromPoint/triangleHeight;
        if (Math.abs(b0b1b2[1]) < tol) b0b1b2[1] = 0.0;
        else if (Math.abs(1.0-b0b1b2[1]) < tol) b0b1b2[1] = 1.0;
        if (b0b1b2[1] < 0.0 || b0b1b2[1] > 1.0)
            return null;

        // b2:
        b0b1b2[2] = 1.0 - b0b1b2[0] - b0b1b2[1];
        if (Math.abs(b0b1b2[2]) < tol) b0b1b2[2] = 0.0;
        else if (Math.abs(1.0-b0b1b2[2]) < tol) b0b1b2[2] = 1.0;
        if (b0b1b2[2] < 0.0)
            return null;
        return b0b1b2;
    }

    private static double distanceFromLine(Vec4 pnt, Vec4 P, Vec4 u)
    {
        // Return distance from pnt to line(P,u)
        // Pythagorean theorem approach: c^2 = a^2 + b^2. The
        // The square of the distance we seek is b^2:
        Vec4 toPoint = pnt.subtract3(P);
        double cSquared = toPoint.dot3(toPoint);
        double aSquared = u.normalize3().dot3(toPoint);
        aSquared *= aSquared;
        double distSquared = cSquared - aSquared;
        if (distSquared < 0.0)
            // must be a tiny number that really ought to be 0.0
            return 0.0;
        return Math.sqrt(distSquared);
    }

    protected DoubleBuffer makeGeographicTexCoords(SectorGeometry sg,
        SectorGeometry.GeographicTextureCoordinateComputer computer)
    {
        if (sg == null)
        {
            String msg = Logging.getMessage("nullValue.SectorGeometryIsNull");
            Logging.logger().severe(msg);
            throw new IllegalArgumentException(msg);
        }

        if (computer == null)
        {
            String msg = Logging.getMessage("nullValue.TextureCoordinateComputerIsNull");
            Logging.logger().severe(msg);
            throw new IllegalArgumentException(msg);
        }

        RectTile rt = (RectTile) sg;

        int density = rt.density;
        if (density < 1)
            density = 1;

        int coordCount = (density + 3) * (density + 3);
        DoubleBuffer p = BufferUtil.newDoubleBuffer(2 * coordCount);

        double deltaLat = rt.sector.getDeltaLatRadians() / density;
        double deltaLon = rt.sector.getDeltaLonRadians() / density;
        Angle minLat = rt.sector.getMinLatitude();
        Angle maxLat = rt.sector.getMaxLatitude();
        Angle minLon = rt.sector.getMinLongitude();
        Angle maxLon = rt.sector.getMaxLongitude();

        double[] uv; // for return values from computer

        int k = 2 * (density + 3);
        for (int j = 0; j < density; j++)
        {
            Angle lat = Angle.fromRadians(minLat.radians + j * deltaLat);

            // skirt column; duplicate first column
            uv = computer.compute(lat, minLon);
            p.put(k++, uv[0]).put(k++, uv[1]);

            // interior columns
            for (int i = 0; i < density; i++)
            {
                Angle lon = Angle.fromRadians(minLon.radians + i * deltaLon);
                uv = computer.compute(lat, lon);
                p.put(k++, uv[0]).put(k++, uv[1]);
            }

            // last interior column; force u to 1.
            uv = computer.compute(lat, maxLon);
            p.put(k++, uv[0]).put(k++, uv[1]);

            // skirt column; duplicate previous column
            p.put(k++, uv[0]).put(k++, uv[1]);
        }

        // Last interior row
        uv = computer.compute(maxLat, minLon); // skirt column
        p.put(k++, uv[0]).put(k++, uv[1]);

        for (int i = 0; i < density; i++)
        {
            Angle lon = Angle.fromRadians(minLon.radians + i * deltaLon); // u
            uv = computer.compute(maxLat, lon);
            p.put(k++, uv[0]).put(k++, uv[1]);
        }

        uv = computer.compute(maxLat, maxLon); // last interior column
        p.put(k++, uv[0]).put(k++, uv[1]);
        p.put(k++, uv[0]).put(k++, uv[1]); // skirt column

        // last skirt row
        int kk = k - 2 * (density + 3);
        for (int i = 0; i < density + 3; i++)
        {
            p.put(k++, p.get(kk++));
            p.put(k++, p.get(kk++));
        }

        // first skirt row
        k = 0;
        kk = 2 * (density + 3);
        for (int i = 0; i < density + 3; i++)
        {
            p.put(k++, p.get(kk++));
            p.put(k++, p.get(kk++));
        }

        return p;
    }

    private static DoubleBuffer getTextureCoordinates(int density)
	{
		if (density < 1)
			density = 1;

		// Approximate 1 to avoid shearing off of right and top skirts in SurfaceTileRenderer.
		// TODO: dig into this more: why are the skirts being sheared off?
		final double one = 0.999999;

		DoubleBuffer p = parameterizations.get(density);
		if (p != null)
			return p;

		int coordCount = (density + 3) * (density + 3);
		p = BufferUtil.newDoubleBuffer(2 * coordCount);
		double delta = 1d / density;
		int k = 2 * (density + 3);
		for (int j = 0; j < density; j++)
		{
			double v = j * delta;

			// skirt column; duplicate first column
			p.put(k++, 0d);
			p.put(k++, v);

			// interior columns
			for (int i = 0; i < density; i++)
			{
				p.put(k++, i * delta); // u
				p.put(k++, v);
			}

			// last interior column; force u to 1.
			p.put(k++, one);//1d);
			p.put(k++, v);

			// skirt column; duplicate previous column
			p.put(k++, one);//1d);
			p.put(k++, v);
		}

		// Last interior row
		//noinspection UnnecessaryLocalVariable
		double v = one;//1d;
		p.put(k++, 0d); // skirt column
		p.put(k++, v);

		for (int i = 0; i < density; i++)
		{
			p.put(k++, i * delta); // u
			p.put(k++, v);
		}
		p.put(k++, one);//1d); // last interior column
		p.put(k++, v);

		p.put(k++, one);//1d); // skirt column
		p.put(k++, v);

		// last skirt row
		int kk = k - 2 * (density + 3);
		for (int i = 0; i < density + 3; i++)
		{
			p.put(k++, p.get(kk++));
			p.put(k++, p.get(kk++));
		}

		// first skirt row
		k = 0;
		kk = 2 * (density + 3);
		for (int i = 0; i < density + 3; i++)
		{
			p.put(k++, p.get(kk++));
			p.put(k++, p.get(kk++));
		}

		parameterizations.put(density, p);

		return p;
	}

	protected static IntBuffer getIndices(int density)
	{
		if (density < 1)
			density = 1;

		// return a pre-computed buffer if possible.
		java.nio.IntBuffer buffer = indexLists.get(density);
		if (buffer != null)
			return buffer;

		int sideSize = density + 2;

		int indexCount = 2 * sideSize * sideSize + 4 * sideSize - 2;
		buffer = BufferUtil.newIntBuffer(indexCount);
		int k = 0;
		for (int i = 0; i < sideSize; i++)
		{
			buffer.put(k);
			if (i > 0)
			{
				buffer.put(++k);
				buffer.put(k);
			}

			if (i % 2 == 0) // even
			{
				buffer.put(++k);
				for (int j = 0; j < sideSize; j++)
				{
					k += sideSize;
					buffer.put(k);
					buffer.put(++k);
				}
			}
			else
			// odd
			{
				buffer.put(--k);
				for (int j = 0; j < sideSize; j++)
				{
					k -= sideSize;
					buffer.put(k);
					buffer.put(--k);
				}
			}
		}

		indexLists.put(density, buffer);

		return buffer;
	}

	//calculates normals quickly, but uses 4 surrounding vertices
	//instead of average of connected faces
	protected static DoubleBuffer getNormalsQuick(int density,
			DoubleBuffer vertices, Vec4 referenceCenter)
	{
		int side = density + 3;
		int numVertices = side * side;
		java.nio.DoubleBuffer normals = BufferUtil
				.newDoubleBuffer(numVertices * 3);
		Vec4 p0, p1, p2, p3, p4;

		//don't calculate skirt normals yet
		for (int j = 1; j < side - 1; j++)
		{
			for (int i = 1; i < side - 1; i++)
			{
				int index0 = j * side + i;
				int index1 = j * side + (i - 1);
				int index2 = j * side + (i + 1);
				int index3 = (j - 1) * side + i;
				int index4 = (j + 1) * side + i;
				p0 = getVec4(index0, vertices, referenceCenter);
				p1 = getVec4(index1, vertices, referenceCenter);
				p2 = getVec4(index2, vertices, referenceCenter);
				p3 = getVec4(index3, vertices, referenceCenter);
				p4 = getVec4(index4, vertices, referenceCenter);

				Vec4 n1 = p0.subtract3(p1).normalize3().cross3(
						p0.subtract3(p3).normalize3());
				Vec4 n2 = p0.subtract3(p2).normalize3().cross3(
						p0.subtract3(p4).normalize3());
				Vec4 normal = n1.add3(n2).normalize3();

				normals.put(index0 * 3, normal.x).put(index0 * 3 + 1, normal.y)
						.put(index0 * 3 + 2, normal.z);
			}
		}

		//copy skirt normals from neighbours
		for (int i = 1; i < side - 1; i++)
		{
			int di = i;
			int si = side + i;
			copyNormalInBuffer(si, di, normals);

			di = side * (side - 1) + i;
			si = side * (side - 2) + i;
			copyNormalInBuffer(si, di, normals);
		}
		for (int i = 0; i < side; i++)
		{
			int di = i * side;
			int si = i * side + 1;
			copyNormalInBuffer(si, di, normals);

			di = i * side + (side - 1);
			si = i * side + (side - 2);
			copyNormalInBuffer(si, di, normals);
		}

		return normals;
	}

	private static Vec4 getVec4(int index, DoubleBuffer vertices,
			Vec4 referenceCenter)
	{
		return new Vec4(vertices.get(index * 3) + referenceCenter.x, vertices
				.get(index * 3 + 1)
				+ referenceCenter.y, vertices.get(index * 3 + 2)
				+ referenceCenter.z);
	}

	private static void copyNormalInBuffer(int srcIndex, int dstIndex,
			DoubleBuffer normals)
	{
		normals.put(dstIndex * 3, normals.get(srcIndex * 3));
		normals.put(dstIndex * 3 + 1, normals.get(srcIndex * 3 + 1));
		normals.put(dstIndex * 3 + 2, normals.get(srcIndex * 3 + 2));
	}

	//computes normals for the triangle strip
	protected static java.nio.DoubleBuffer getNormals(int density,
			DoubleBuffer vertices, java.nio.IntBuffer indices,
			Vec4 referenceCenter)
	{
		int side = density + 3;
		int numVertices = side * side;
		int numFaces = indices.limit() - 2;
		double centerX = referenceCenter.x;
		double centerY = referenceCenter.y;
		double centerZ = referenceCenter.z;
		// Create normal buffer
		java.nio.DoubleBuffer normals = BufferUtil
				.newDoubleBuffer(numVertices * 3);
		int[] counts = new int[numVertices];
		Vec4[] norms = new Vec4[numVertices];
		for (int i = 0; i < numVertices; i++)
			norms[i] = new Vec4(0d);

		for (int i = 0; i < numFaces; i++)
		{
			//get vertex indices
			int index0 = indices.get(i);
			int index1 = indices.get(i + 1);
			int index2 = indices.get(i + 2);

			//get verts involved in current face
			Vec4 v0 = new Vec4(vertices.get(index0 * 3) + centerX, vertices
					.get(index0 * 3 + 1)
					+ centerY, vertices.get(index0 * 3 + 2) + centerZ);

			Vec4 v1 = new Vec4(vertices.get(index1 * 3) + centerX, vertices
					.get(index1 * 3 + 1)
					+ centerY, vertices.get(index1 * 3 + 2) + centerZ);

			Vec4 v2 = new Vec4(vertices.get(index2 * 3) + centerX, vertices
					.get(index2 * 3 + 1)
					+ centerY, vertices.get(index2 * 3 + 2) + centerZ);

			// get triangle edge vectors and plane normal
			Vec4 e1 = v1.subtract3(v0), e2;
			if (i % 2 == 0)
				e2 = v2.subtract3(v0);
			else
				e2 = v0.subtract3(v2);
			Vec4 N = e1.cross3(e2).normalize3(); // if N is 0, the triangle is degenerate

            if (N.getLength3() > 0)
            {
                // Store the face's normal for each of the vertices that make up the face.
                norms[index0] = norms[index0].add3(N);
                norms[index1] = norms[index1].add3(N);
                norms[index2] = norms[index2].add3(N);

                //increment vertex normal counts
                counts[index0]++;
                counts[index1]++;
                counts[index2]++;
            }
        }

		// Now loop through each vertex, and average out all the normals stored.
		for (int i = 0; i < numVertices; i++)
		{
            if (counts[i] > 0)
                norms[i] = norms[i].divide3(counts[i]).normalize3();
			int index = i * 3;
			normals.put(index++, norms[i].x).put(index++, norms[i].y).put(
					index, norms[i].z);
		}

		return normals;
	}

	// TODO: The following method was brought over from BasicRectangularTessellator and is unchecked.
	// Compute normals for a strip
	/*protected static java.nio.DoubleBuffer getNormals(int density,
			DoubleBuffer vertices, java.nio.IntBuffer indices,
			Vec4 referenceCenter)
	{
		int numVertices = (density + 3) * (density + 3);
		int sideSize = density + 2;
		int numFaces = indices.limit() - 2;
		double centerX = referenceCenter.x;
		double centerY = referenceCenter.y;
		double centerZ = referenceCenter.z;
		// Create normal buffer
		java.nio.DoubleBuffer normals = BufferUtil
				.newDoubleBuffer(numVertices * 3);
		// Create per vertex normal lists
		List<List<Vec4>> normalLists = new ArrayList<List<Vec4>>(numVertices);
		for (int i = 0; i < numVertices; i++)
			normalLists.add(i, new ArrayList<Vec4>());
		// Go through all faces in the strip and store normals in lists
		for (int i = 0; i < numFaces; i++)
		{
			int vIndex = 3 * indices.get(i);
			Vec4 v0 = new Vec4((vertices.get(vIndex++) + centerX), (vertices
					.get(vIndex++) + centerY), (vertices.get(vIndex) + centerZ));

			vIndex = 3 * indices.get(i + 1);
			Vec4 v1 = new Vec4((vertices.get(vIndex++) + centerX), (vertices
					.get(vIndex++) + centerY), (vertices.get(vIndex) + centerZ));

			vIndex = 3 * indices.get(i + 2);
			Vec4 v2 = new Vec4((vertices.get(vIndex++) + centerX), (vertices
					.get(vIndex++) + centerY), (vertices.get(vIndex) + centerZ));

			// get triangle edge vectors and plane normal
			Vec4 e1 = v1.subtract3(v0);
			Vec4 e2 = v2.subtract3(v0);
			Vec4 N = e1.cross3(e2).normalize3(); // if N is 0, the triangle is degenerate

			// Store the face's normal for each of the vertices that make up the face.
			// TODO: Clear up warnings here
			normalLists.get(indices.get(i)).add(N);
			normalLists.get(indices.get(i + 1)).add(N);
			normalLists.get(indices.get(i + 2)).add(N);
			//System.out.println("Normal: " + N);
		}

		// Now loop through each vertex, and average out all the normals stored.
		int idx = 0;
		for (int i = 0; i < numVertices; i++)
		{
			Vec4 normal = Vec4.ZERO;
			// Sum
			for (int j = 0; j < normalLists.get(i).size(); ++j)
				normal = normal.add3(normalLists.get(i).get(j));
			// Average
			normal = normal.multiply3(1.0f / normalLists.get(i).size())
					.normalize3();
			// Fill normal buffer
			normals.put(idx++, normal.x);
			normals.put(idx++, normal.y);
			normals.put(idx++, normal.z);
			//System.out.println("Normal: " + normal + " - " + normalLists[i].size());
			//System.out.println("Normal buffer: " + normals.get(idx - 3) + ", " + normals.get(idx - 2) + ", " + normals.get(idx - 1));
		}

		return normals;
	}*/


private SectorGeometry.ExtractedShapeDescription getIntersectingTessellationPieces(RectTile tile, Plane[] planes)
    {
        tile.ri.vertices.rewind();
        tile.ri.indices.rewind();

        Vec4 offset = tile.ri.referenceCenter;
        if (offset == null)
            offset = new Vec4(0.0);

        int trianglesNum = tile.ri.indices.capacity() - 2;

        int[] indices = new int[3];
        double[] coords = new double[3];

        SectorGeometry.ExtractedShapeDescription clippedTriangleList = null;
        for (int i = 0; i < trianglesNum; i++)
        {
            tile.ri.indices.position(i);
            tile.ri.indices.get(indices);

            if ((indices[0] == indices[1]) || (indices[0] == indices[2]) ||
                (indices[1] == indices[2]))
                // degenerate triangle
                continue;
            Vec4[] triVerts = new Vec4[3];
            for (int j=0 ; j<3 ; j++)
            {
                tile.ri.vertices.position(3 * indices[j]);
                tile.ri.vertices.get(coords);
                triVerts[j] = new Vec4(coords[0] + offset.getX(),
                    coords[1] + offset.getY(),
                    coords[2] + offset.getZ(),1.0);
            }
            clippedTriangleList = addClippedPolygon(triVerts,planes,clippedTriangleList);
        }
        return clippedTriangleList;
    }

    private SectorGeometry.ExtractedShapeDescription addClippedPolygon(Vec4[] triVerts, Plane[] planes,
        SectorGeometry.ExtractedShapeDescription l)
    {
        // Clip the polygon defined by polyVerts to the region defined by the intersection of
        // the negative halfspaces in 'planes'. If there is a non-empty clipped result, then
        // add it to the given list.
        // This routine is (currently) only used to clip triangles in the current tessellation,
        // but it is actually general enough for n-sided polygons. Best results will be
        // obtained if the polygon is convex.

        // ignore triangles on skirts
        if (isSkirt(triVerts))
            return l;

        // We use a multi-pass Sutherland-Hodgman-style clipping algorithm.
        // There is one pass for each clipping plane. We begin by copying the
        // original vertices to local working storage.
        Vec4[] polyVerts = new Vec4[3];
        System.arraycopy(triVerts,0,polyVerts,0,3);

        for (Plane p : planes)
        {
            polyVerts = doSHPass(p,polyVerts);
            if (polyVerts == null)
                // the polygon has been totally clipped away
                return l;
        }
        // some part of the polygon survived. Store it in the list.
        if (l ==  null)
            l = new SectorGeometry.ExtractedShapeDescription(
                    new ArrayList<Vec4[]>(), new ArrayList<SectorGeometry.BoundaryEdge>());
        l.interiorPolys.add(polyVerts);
        addBoundaryEdges(polyVerts,triVerts,l.shapeOutline);

        return l;
    }

    private boolean isSkirt(Vec4[] triVerts)
    {
        Vec4 normal = globe.computeSurfaceNormalAtPoint(triVerts[0]);
        // try to minimize numerical roundoff. The three triangle vertices
        // are going to have coordinates with roughly the same magnitude,
        // so we just sample triVerts[0].
        double maxC = Math.max(Math.abs(triVerts[0].x),Math.abs(triVerts[0].y));
        maxC = Math.max(maxC,Math.abs(triVerts[0].z));
        Vec4 v0 = triVerts[0].divide3(maxC);
        Vec4 u = triVerts[1].divide3(maxC).subtract3(v0);
        Vec4 v = triVerts[triVerts.length-1].divide3(maxC).subtract3(v0);
        Vec4 w = u.cross3(v).normalize3();
        return (Math.abs(w.dot3(normal)) < 0.0001);
    }

    private Vec4[] doSHPass(Plane p, Vec4[] polyVerts)
    {
        // See comments in addClippedPolygon. Also note that, even if the
        // original polygon is a triangle, the polygon here may have
        // more than three vertices, depending on how it cuts the various
        // planes whose volumetric intersection defines the clipping region.
        ArrayList<Vec4> workingStorage = new ArrayList<Vec4>();
        Vec4 startPnt = polyVerts[0];
        boolean startPntIn = (p.dot(startPnt) <= 0.0);
        for (int i=1 ; i<=polyVerts.length ; i++)
        {
            if (startPntIn)
                workingStorage.add(startPnt);
            Vec4 endPnt = polyVerts[i%polyVerts.length];
            boolean endPntIn = (p.dot(endPnt) <= 0.0);
            if (startPntIn != endPntIn)
            {
                // compute and store the intersection of this edge with p
                Vec4[] clippedPnts;
                if (startPntIn)
                    clippedPnts = p.clip(startPnt,endPnt);
                else
                    clippedPnts = p.clip(endPnt,startPnt);
                workingStorage.add(clippedPnts[0]);
            }
            // prepare for next edge
            startPnt = endPnt;
            startPntIn = endPntIn;
        }
        if (workingStorage.size() == 0)
            return null;
        Vec4[] verts = new Vec4[workingStorage.size()];
        return workingStorage.toArray(verts);
    }

    private void addBoundaryEdges(Vec4[] polyVerts, Vec4[] triVerts,
        ArrayList<SectorGeometry.BoundaryEdge> beList)
    {
        // each edge of polyVerts not coincident with an edge of the original
        // triangle (triVerts) belongs to the outer boundary.
        for (int i=0 ; i<polyVerts.length ; i++)
        {
            int j = (i + 1) % polyVerts.length;
            if (!edgeOnTriangle(polyVerts[i],polyVerts[j],triVerts))
                beList.add(new SectorGeometry.BoundaryEdge(polyVerts,i,j));
        }
    }

    private boolean edgeOnTriangle(Vec4 a, Vec4 b, Vec4[] tri)
    {
        final double tol = 1.0e-4;
        double[] coords_a = baryCentricCoordsRequireInside(a,tri);
        double[] coords_b = baryCentricCoordsRequireInside(b,tri);
        if ( (coords_a == null) || (coords_b == null) )
            // mathematically not possible because 'a' and 'b' are
            // known to be on edges of the triangle 'tri'.
            return true;
        for (int i=0 ; i<3 ; i++)
            if ( (coords_a[i] < tol) && (coords_b[i] < tol) )
                // 'a' and 'b' are on the same edge
                return true;
        return false;
    }
    
    private SectorGeometry.ExtractedShapeDescription getIntersectingTessellationPieces(RectTile tile, Vec4 Cxyz,
            Vec4 uHat, Vec4 vHat, double uRadius, double vRadius)
    {
        tile.ri.vertices.rewind();
        tile.ri.indices.rewind();

        Vec4 offset = tile.ri.referenceCenter;
        if (offset == null)
            offset = new Vec4(0.0);

        int trianglesNum = tile.ri.indices.capacity() - 2;

        int[] indices = new int[3];
        double[] coords = new double[3];

        SectorGeometry.ExtractedShapeDescription clippedTriangleList = null;
        for (int i = 0; i < trianglesNum; i++)
        {
            tile.ri.indices.position(i);
            tile.ri.indices.get(indices);

            if ((indices[0] == indices[1]) || (indices[0] == indices[2]) ||
                (indices[1] == indices[2]))
                // degenerate triangle
                continue;
            Vec4[] triVerts = new Vec4[3];
            for (int j=0 ; j<3 ; j++)
            {
                tile.ri.vertices.position(3 * indices[j]);
                tile.ri.vertices.get(coords);
                triVerts[j] = new Vec4(coords[0] + offset.getX(),
                    coords[1] + offset.getY(),
                    coords[2] + offset.getZ(),1.0);
            }
            clippedTriangleList = addClippedPolygon(triVerts,
                Cxyz,uHat,vHat,uRadius,vRadius,clippedTriangleList);
        }
        return clippedTriangleList;
    }
    
    private SectorGeometry.ExtractedShapeDescription addClippedPolygon(Vec4[] polyVerts, Vec4 Cxyz,
            Vec4 uHat, Vec4 vHat, double uRadius, double vRadius, SectorGeometry.ExtractedShapeDescription l)
    {
        // ignore triangles on skirts
        if (isSkirt(polyVerts))
            return l;

        int i = 0, nInNegHalfspace = 0, locIn = -1, locOut = -1;
        for (Vec4 vtx : polyVerts)
        {
            Vec4 vMinusC = vtx.subtract3(Cxyz);
            double xd = vMinusC.dot3(uHat);
            double yd = vMinusC.dot3(vHat);
            double halfspaceEqn = (xd*xd)/(uRadius*uRadius) + (yd*yd)/(vRadius*vRadius) - 1.0;
            if (halfspaceEqn <= 0.0)
            {
                locIn = i++;
                nInNegHalfspace++;
            }
            else
                locOut = i++;
        }
        SectorGeometry.BoundaryEdge be = new SectorGeometry.BoundaryEdge(null,-1,-1);
        switch (nInNegHalfspace)
        {
            case 0: // check for edge intersections
                polyVerts = checkForEdgeCylinderIntersections(polyVerts,Cxyz,uHat,vHat,
                    uRadius,vRadius);
                break;
            case 1: // compute and return a trimmed triangle
                if (locIn != 0)
                {
                    Vec4 h1 = polyVerts[locIn]; polyVerts[locIn] = polyVerts[0];
                    polyVerts[0] = h1;
                }
                polyVerts = computeTrimmedPoly(polyVerts,Cxyz,uHat,vHat,uRadius,
                    vRadius,nInNegHalfspace,be);
                break;
            case 2: // compute and return a trimmed quadrilateral
                if (locOut != 0)
                {
                    Vec4 h2 = polyVerts[locOut]; polyVerts[locOut] = polyVerts[0];
                    polyVerts[0] = h2;
                }
                polyVerts = computeTrimmedPoly(polyVerts,Cxyz,uHat,vHat,uRadius,
                    vRadius,nInNegHalfspace,be);
                break;
            case 3: // triangle completely inside cylinder, so store it
                break;
        }
        if (polyVerts == null)
            return l;
        if (l == null)
            l = new SectorGeometry.ExtractedShapeDescription(new ArrayList<Vec4[]>(100),
                new ArrayList<SectorGeometry.BoundaryEdge>(50));
        l.interiorPolys.add(polyVerts);
        if (be.vertices != null)
            l.shapeOutline.add(be);
        return l;
    }

    // TODO: Why is this empty method here?
    private Vec4[] checkForEdgeCylinderIntersections(Vec4[] polyVerts, Vec4 Cxyz,
            Vec4 uHat, Vec4 vHat, double uRadius, double vRadius)
    {
        // no triangle vertices are inside the cylinder; see if there are edge intersections
        // this will only be the case if the cylinder's size is roughly the same as a triangle
        // in the current tessellation and may not be worth the extra computation to check.
        return null;
    }

    private Vec4[] computeTrimmedPoly(Vec4[] polyVerts, Vec4 Cxyz,
            Vec4 uHat, Vec4 vHat, double uRadius, double vRadius, int nInside,
            SectorGeometry.BoundaryEdge be)
    {
        // Either 1 or 2 vertices are inside the ellipse. If exactly 1 is inside, it is in position 0
        // of the array. If exactly 1 is outside, it is in position 0 of the array.
        // We therefore compute the points of intersection between the two edges [0]-[1] and [0]-[2]
        // with the cylinder and return either a triangle or a quadrilateral.
        Vec4 p1 = intersectWithEllCyl(polyVerts[0],polyVerts[1],Cxyz,uHat,vHat,uRadius,vRadius);
        Vec4 p2 = intersectWithEllCyl(polyVerts[0],polyVerts[2],Cxyz,uHat,vHat,uRadius,vRadius);
        Vec4 midP1P2 = p1.multiply3(0.5).add3(p2.multiply3(0.5));
        if (nInside == 1)
        {
            polyVerts[1] = p1; polyVerts[2] = p2;
            be.vertices = polyVerts; be.i1 = 1; be.i2 = 2; be.toMidPoint = midP1P2.subtract3(polyVerts[0]);
            return polyVerts;
        }
        Vec4[] ret = new Vec4[4];
        ret[0] = p1; ret[1] = polyVerts[1];
        ret[2] = polyVerts[2]; ret[3] = p2;
        be.vertices = ret; be.i1 = 0; be.i2 = 3; be.toMidPoint = polyVerts[0].subtract3(midP1P2);
        return ret;
    }

    private Vec4 intersectWithEllCyl(Vec4 v0, Vec4 v1, Vec4 Cxyz,
        Vec4 uHat, Vec4 vHat, double uRadius, double vRadius)
    {
        // Entry condition: one of (v0, v1) is inside the elliptical cylinder, and one is
        // outside. We find 0<t<1 such that (1-t)*v0 + t*v1 is on the cylinder. We then return
        // the corresponding point.

        // First project v0 and v1 onto the plane of the ellipse
        Vec4 v0MinusC = v0.subtract3(Cxyz);
        double v0x = v0MinusC.dot3(uHat);
        double v0y = v0MinusC.dot3(vHat);
        Vec4 v1MinusC = v1.subtract3(Cxyz);
        double v1x = v1MinusC.dot3(uHat);
        double v1y = v1MinusC.dot3(vHat);

        // Then compute the coefficients of the quadratic equation describing where
        // the line segment (v0x,v0y)-(v1x,v1y) intersects the ellipse in the plane:
        double v1xMinusV0x = v1x - v0x;
        double v1yMinusV0y = v1y - v0y;
        double uRsquared = uRadius * uRadius;
        double vRsquared = vRadius * vRadius;

        double a = v1xMinusV0x*v1xMinusV0x/uRsquared + v1yMinusV0y*v1yMinusV0y/vRsquared;
        double b = 2.0 * (v0x*v1xMinusV0x/uRsquared + v0y*v1yMinusV0y/vRsquared);
        double c = v0x*v0x/uRsquared + v0y*v0y/vRsquared - 1.0;

        // now solve it
        // if the entry condition is satsfied, the diuscriminant will not be negative...
        double disc = Math.sqrt(b*b - 4.0*a*c);
        double t = (-b + disc)/(2.0*a);
        if ( (t < 0.0) || (t > 1.0) )
            // need the other root
            t = (-b - disc)/(2.0*a);

        // the desired point is obtained by using the computed t with the original points
        // v0 and v1:
        return v0.multiply3(1.0-t).add3(v1.multiply3(t));
    }


    
    protected void renderTileID(DrawContext dc, RectTile tile)
    {
        java.awt.Rectangle viewport = dc.getView().getViewport();
        TextRenderer textRenderer = OGLTextRenderer.getOrCreateTextRenderer(dc.getTextRendererCache(),
            java.awt.Font.decode("Arial-Plain-15"));

        GL gl = dc.getGL();
        OGLStackHandler ogsh = new OGLStackHandler();

        try
        {
            ogsh.pushAttrib(gl, GL.GL_ENABLE_BIT);

            dc.getGL().glDisable(GL.GL_DEPTH_TEST);
            dc.getGL().glDisable(GL.GL_BLEND);

            textRenderer.beginRendering(viewport.width, viewport.height);
            textRenderer.setColor(Color.RED);
            String tileLabel = Integer.toString(tile.level);
            double[] elevs = this.globe.getMinAndMaxElevations(tile.getSector());
            if (elevs != null)
                tileLabel += ", " + (int) elevs[0] + "/" + (int) elevs[1];

            LatLon ll = tile.getSector().getCentroid();
            Vec4 pt = dc.getGlobe().computePointFromPosition(ll.getLatitude(), ll.getLongitude(),
                dc.getGlobe().getElevation(ll.getLatitude(), ll.getLongitude()));
            pt = dc.getView().project(pt);
            textRenderer.draw(tileLabel, (int) pt.x, (int) pt.y);
            textRenderer.setColor(Color.WHITE);
            textRenderer.endRendering();
        }
        finally
        {
            ogsh.pop(gl);
        }
    }
    
    protected boolean bindVbos(DrawContext dc, RectTile tile, int numTextureUnits)
    {
        int[] verticesVboId = (int[]) dc.getGpuResourceCache().get(tile.ri.vboCacheKey);
        if (verticesVboId == null)
        {
            tile.ri.fillVerticesVBO(dc);
            verticesVboId = (int[]) dc.getGpuResourceCache().get(tile.ri.vboCacheKey);
            if (verticesVboId == null)
                return false;
        }

        GL gl = dc.getGL();

        // Bind vertices
        gl.glBindBuffer(GL.GL_ARRAY_BUFFER, verticesVboId[0]);
        gl.glVertexPointer(3, GL.GL_FLOAT, 0, 0);

        // Bind texture coordinates
        if (numTextureUnits > 0)
        {
            Object texCoordsVboCacheKey = textureCoordVboCacheKeys.get(tile.density);
            int[] texCoordsVboId = (int[])
                (texCoordsVboCacheKey != null ? dc.getGpuResourceCache().get(texCoordsVboCacheKey) : null);
            if (texCoordsVboId == null)
                texCoordsVboId = this.fillTextureCoordsVbo(dc, tile.density, tile.ri.texCoords);
            for (int i = 0; i < numTextureUnits; i++)
            {
                gl.glClientActiveTexture(GL.GL_TEXTURE0 + i);
                gl.glEnableClientState(GL.GL_TEXTURE_COORD_ARRAY);

                gl.glBindBuffer(GL.GL_ARRAY_BUFFER, texCoordsVboId[0]);
                gl.glTexCoordPointer(2, GL.GL_FLOAT, 0, 0);
            }
        }

        // Bind index list
        Object indexListVboCacheKey = indexListsVboCacheKeys.get(tile.density);
        int[] indexListVboId = (int[])
            (indexListVboCacheKey != null ? dc.getGpuResourceCache().get(indexListVboCacheKey) : null);
        if (indexListVboId == null)
            indexListVboId = this.fillIndexListVbo(dc, tile.density, tile.ri.indices);
        if (indexListVboId != null)
            gl.glBindBuffer(GL.GL_ELEMENT_ARRAY_BUFFER, indexListVboId[0]);

        return indexListVboId != null;
    }
    
    protected int[] fillIndexListVbo(DrawContext dc, int density, IntBuffer indices)
    {
        GL gl = dc.getGL();

        Object indexListVboCacheKey = indexListsVboCacheKeys.get(density);
        int[] indexListVboId = (int[])
            (indexListVboCacheKey != null ? dc.getGpuResourceCache().get(indexListVboCacheKey) : null);
        if (indexListVboId == null)
        {
            indexListVboId = new int[1];
            gl.glGenBuffers(indexListVboId.length, indexListVboId, 0);

            if (indexListVboCacheKey == null)
            {
                indexListVboCacheKey = new Object();
                indexListsVboCacheKeys.put(density, indexListVboCacheKey);
            }

            int size = indices.limit() * 4;
            dc.getGpuResourceCache().put(indexListVboCacheKey, indexListVboId, GpuResourceCache.VBO_BUFFERS, size);
        }

        try
        {
            gl.glBindBuffer(GL.GL_ELEMENT_ARRAY_BUFFER, indexListVboId[0]);
            gl.glBufferData(GL.GL_ELEMENT_ARRAY_BUFFER, indices.limit() * 4, indices.rewind(), GL.GL_STATIC_DRAW);
        }
        finally
        {
            gl.glBindBuffer(GL.GL_ELEMENT_ARRAY_BUFFER, 0);
        }

        return indexListVboId;
    }

    protected int[] fillTextureCoordsVbo(DrawContext dc, int density, DoubleBuffer texCoords)
    {
        GL gl = dc.getGL();

        Object texCoordVboCacheKey = textureCoordVboCacheKeys.get(density);
        int[] texCoordVboId = (int[])
            (texCoordVboCacheKey != null ? dc.getGpuResourceCache().get(texCoordVboCacheKey) : null);
        if (texCoordVboId == null)
        {
            texCoordVboId = new int[1];
            gl.glGenBuffers(texCoordVboId.length, texCoordVboId, 0);

            if (texCoordVboCacheKey == null)
            {
                texCoordVboCacheKey = new Object();
                textureCoordVboCacheKeys.put(density, texCoordVboCacheKey);
            }

            int size = texCoords.limit() * 4;
            dc.getGpuResourceCache().put(texCoordVboCacheKey, texCoordVboId, GpuResourceCache.VBO_BUFFERS, size);
        }

        try
        {
            gl.glBindBuffer(GL.GL_ARRAY_BUFFER, texCoordVboId[0]);
            gl.glBufferData(GL.GL_ARRAY_BUFFER, texCoords.limit() * 4, texCoords.rewind(), GL.GL_STATIC_DRAW);
        }
        finally
        {
            gl.glBindBuffer(GL.GL_ARRAY_BUFFER, 0);
        }

        return texCoordVboId;
    }
    
  public static class RectTile
    implements SectorGeometry
  {/*

        private static void access$1502(RectTile paramRectTile, RenderInfo renderInfo) {
            throw new UnsupportedOperationException("Not yet implemented");
        }

        private static Object access$1500(RectTile paramRectTile) {
            throw new UnsupportedOperationException("Not yet implemented");
        }

        private static void access$2602(RectTile paramRectTile, int rGB) {
            throw new UnsupportedOperationException("Not yet implemented");
        }*/
    private final RectangularNormalTessellator tessellator;
    private final int level;
    private final Sector sector;
    private final int density;
    private final double log10CellSize;
    private Extent extent;
    private RectangularNormalTessellator.RenderInfo ri;
    private int minColorCode = 0;
    private int maxColorCode = 0;

    public RectTile(RectangularNormalTessellator paramRectangularNormalTessellator, Extent paramExtent, int paramInt1, int paramInt2, Sector paramSector, double paramDouble)
    {
      this.tessellator = paramRectangularNormalTessellator;
      this.level = paramInt1;
      this.density = paramInt2;
      this.sector = paramSector;
      this.extent = paramExtent;
      this.log10CellSize = Math.log10(paramDouble);
    }

    public Sector getSector()
		{
			return this.sector;
		}

		public Extent getExtent()
		{
			return this.extent;
		}

		public void renderMultiTexture(DrawContext dc, int numTextureUnits)
		{
			this.tessellator.renderMultiTexture(dc, this, numTextureUnits);
		}

		public void render(DrawContext dc)
		{
			this.tessellator.render(dc, this);
		}

		public void renderWireframe(DrawContext dc, boolean showTriangles,
				boolean showTileBoundary)
		{
			this.tessellator.renderWireframe(dc, this, showTriangles,
					showTileBoundary);
		}

		public void renderBoundingVolume(DrawContext dc)
		{
			this.tessellator.renderBoundingVolume(dc, this);
		}

		public PickedObject[] pick(DrawContext dc, List<? extends Point> pickPoints)
		{
			return this.tessellator.pick(dc, this, pickPoints);
		}

		public void pick(DrawContext dc, Point pickPoint)
		{
			this.tessellator.pick(dc, this, pickPoint);
		}

		public Vec4 getSurfacePoint(Angle latitude, Angle longitude,
				double metersOffset)
		{
			return this.tessellator.getSurfacePoint(this, latitude, longitude,
					metersOffset);
		}

        public double getResolution()
        {
            return this.sector.getDeltaLatRadians() / this.density;
        }

        public Intersection[] intersect(Line line)
        {
            return this.tessellator.intersect(this, line);
        }

        public Intersection[] intersect(double elevation)
        {
            return this.tessellator.intersect(this,elevation);
        }

        public DoubleBuffer makeTextureCoordinates(GeographicTextureCoordinateComputer computer)
        {
            return this.tessellator.makeGeographicTexCoords(this, computer);
        }

        public ExtractedShapeDescription getIntersectingTessellationPieces(Plane[] p)
        {
            return this.tessellator.getIntersectingTessellationPieces(this,p);
        }

        public ExtractedShapeDescription getIntersectingTessellationPieces(Vec4 Cxyz,
            Vec4 uHat, Vec4 vHat, double uRadius, double vRadius)
        {
            return this.tessellator.getIntersectingTessellationPieces(this,Cxyz,
                uHat,vHat,uRadius,vRadius);
        }

        
        public void beginRendering(DrawContext dc, int numTextureUnits)
        {
            dc.getView().setReferenceCenter(dc, ri.referenceCenter);
            if (dc.getGLRuntimeCapabilities().isUseVertexBufferObject())
            {
                if (this.tessellator.bindVbos(dc, this, numTextureUnits))
                    this.ri.isVboBound = true;
            }
        }    
 
             
            
        public void endRendering(DrawContext dc)
        {
            if (this.ri.isVboBound)
            {
                dc.getGL().glBindBuffer(GL.GL_ARRAY_BUFFER, 0);
                this.ri.isVboBound = false;
            }
        }


        
         public void renderTileID(DrawContext dc)
        {
            this.tessellator.renderTileID(dc, this);
        }
        
         
        public void renderMultiTexture(DrawContext dc, int numTextureUnits, boolean beginRenderingCalled)
        {
            if (beginRenderingCalled)
            {
                this.tessellator.renderMultiTexture(dc, this, numTextureUnits);
            }
            else
            {
                this.beginRendering(dc, numTextureUnits);
                this.tessellator.renderMultiTexture(dc, this, numTextureUnits);
                this.endRendering(dc);
            }
        }
        
         public void render(DrawContext dc, boolean beginRenderingCalled)
        {
            if (beginRenderingCalled)
            {
                this.tessellator.render(dc, this);
            }
            else
            {
                this.beginRendering(dc, 1);
                this.tessellator.render(dc, this);
                this.endRendering(dc);
            }
        }
    public int getLevel()
    {
        return this.level;
    }
  }

  protected static class RenderInfo
    {
        private final int density;
        private final Vec4 referenceCenter;
        private final DoubleBuffer vertices;
        private final DoubleBuffer normals;
        private final DoubleBuffer texCoords;
        private final IntBuffer indices;
        private final long time;
        protected Object vboCacheKey = new Object();
        protected boolean isVboBound = false;

        private RenderInfo(int density, DoubleBuffer vertices, DoubleBuffer texCoords, DoubleBuffer normals, Vec4 refCenter)
        {
            this.density = density;
            this.vertices = vertices;
            this.texCoords = texCoords;
            this.referenceCenter = refCenter;
            this.indices = getIndices(this.density);
            this.normals = normals;
            this.time = System.currentTimeMillis();
        }

        private long getSizeInBytes()
        {
            // Texture coordinates are shared among all tiles of the same density, so do not count towards size.
            // 8 references, doubles in buffer.
            return 8 * 4 + (this.vertices.limit()) * Double.SIZE;
        }
    
 
  protected void fillVerticesVBO(DrawContext dc)
        {
            GL gl = dc.getGL();

            int[] vboIds = (int[]) dc.getGpuResourceCache().get(this.vboCacheKey);
            if (vboIds == null)
            {
                vboIds = new int[1];
                gl.glGenBuffers(vboIds.length, vboIds, 0);
                int size = this.vertices.limit() * 4;
                dc.getGpuResourceCache().put(this.vboCacheKey, vboIds, GpuResourceCache.VBO_BUFFERS, size);
            }

            try
            {
                DoubleBuffer vb = this.vertices;
                gl.glBindBuffer(GL.GL_ARRAY_BUFFER, vboIds[0]);
                gl.glBufferData(GL.GL_ARRAY_BUFFER, vb.limit() * 4, vb.rewind(), GL.GL_STATIC_DRAW);
            }
            finally
            {
                gl.glBindBuffer(GL.GL_ARRAY_BUFFER, 0);
            }
        }
  }
}