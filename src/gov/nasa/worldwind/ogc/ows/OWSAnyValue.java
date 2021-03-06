/* Copyright (C) 2001, 2011 United States Government as represented by
the Administrator of the National Aeronautics and Space Administration.
All Rights Reserved.
*/
package gov.nasa.worldwind.ogc.ows;

/**
 * Parses an OGC Web Service Common (OWS) AnyValue element and provides access to its contents. See
 * http://schemas.opengis.net/ows/2.0/owsDomainType.xsd.
 *
 * @author dcollins
 * @version $Id: OWSAnyValue.java 14946 2011-03-11 23:25:18Z dcollins $
 */
public class OWSAnyValue extends OWSPossibleValues
{
    public OWSAnyValue(String namespaceURI)
    {
        super(namespaceURI);
    }

    @Override
    public boolean isAnyValue()
    {
        return true;
    }

    @Override
    public OWSAnyValue asAnyValue()
    {
        return this;
    }
}
