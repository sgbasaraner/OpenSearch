/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
/*
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.common.geo;

import org.locationtech.jts.geom.Coordinate;
import org.opensearch.OpenSearchParseException;
import org.opensearch.common.geo.builders.CircleBuilder;
import org.opensearch.common.geo.builders.CoordinatesBuilder;
import org.opensearch.common.geo.builders.EnvelopeBuilder;
import org.opensearch.common.geo.builders.GeometryCollectionBuilder;
import org.opensearch.common.geo.builders.LineStringBuilder;
import org.opensearch.common.geo.builders.MultiLineStringBuilder;
import org.opensearch.common.geo.builders.MultiPointBuilder;
import org.opensearch.common.geo.builders.MultiPolygonBuilder;
import org.opensearch.common.geo.builders.PointBuilder;
import org.opensearch.common.geo.builders.PolygonBuilder;
import org.opensearch.common.geo.builders.ShapeBuilder;
import org.opensearch.common.geo.builders.ShapeBuilder.Orientation;
import org.opensearch.common.geo.parsers.CoordinateNode;
import org.opensearch.common.io.stream.NamedWriteableRegistry.Entry;
import org.opensearch.common.unit.DistanceUnit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Enumeration that lists all {@link GeoShapeType}s that can be parsed and indexed
 */
public enum GeoShapeType {
    POINT("point") {
        @Override
        public PointBuilder getBuilder(CoordinateNode coordinates, DistanceUnit.Distance radius,
                                       Orientation orientation, boolean coerce) {
            return new PointBuilder().coordinate(validate(coordinates, coerce).coordinate);
        }

        @Override
        CoordinateNode validate(CoordinateNode coordinates, boolean coerce) {
            if (coordinates.isEmpty()) {
                throw new OpenSearchParseException(
                    "invalid number of points (0) provided when expecting a single coordinate ([lat, lng])");
            } else if (coordinates.children != null) {
                throw new OpenSearchParseException("multipoint data provided when single point data expected.");
            }
            return coordinates;
        }
    },
    MULTIPOINT("multipoint") {
        @Override
        public MultiPointBuilder getBuilder(CoordinateNode coordinates, DistanceUnit.Distance radius,
                                       Orientation orientation, boolean coerce) {
            validate(coordinates, coerce);
            CoordinatesBuilder coordinatesBuilder = new CoordinatesBuilder();
            for (CoordinateNode node : coordinates.children) {
                coordinatesBuilder.coordinate(node.coordinate);
            }
            return new MultiPointBuilder(coordinatesBuilder.build());
        }

        @Override
        CoordinateNode validate(CoordinateNode coordinates, boolean coerce) {
            if (coordinates.children == null || coordinates.children.isEmpty()) {
                if (coordinates.coordinate != null) {
                    throw new OpenSearchParseException("single coordinate found when expecting an array of " +
                        "coordinates. change type to point or change data to an array of >0 coordinates");
                }
                throw new OpenSearchParseException("no data provided for multipoint object when expecting " +
                    ">0 points (e.g., [[lat, lng]] or [[lat, lng], ...])");
            } else {
                for (CoordinateNode point : coordinates.children) {
                    POINT.validate(point, coerce);
                }
            }
            return coordinates;
        }

    },
    LINESTRING("linestring") {
        @Override
        public LineStringBuilder getBuilder(CoordinateNode coordinates, DistanceUnit.Distance radius,
                                       Orientation orientation, boolean coerce) {
            validate(coordinates, coerce);
            CoordinatesBuilder line = new CoordinatesBuilder();
            for (CoordinateNode node : coordinates.children) {
                line.coordinate(node.coordinate);
            }
            return new LineStringBuilder(line);
        }

        @Override
        CoordinateNode validate(CoordinateNode coordinates, boolean coerce) {
            if (coordinates.children.size() < 2) {
                throw new OpenSearchParseException("invalid number of points in LineString (found [{}] - must be >= 2)",
                    coordinates.children.size());
            }
            return coordinates;
        }
    },
    MULTILINESTRING("multilinestring") {
        @Override
        public MultiLineStringBuilder getBuilder(CoordinateNode coordinates, DistanceUnit.Distance radius,
                                       Orientation orientation, boolean coerce) {
            validate(coordinates, coerce);
            MultiLineStringBuilder multiline = new MultiLineStringBuilder();
            for (CoordinateNode node : coordinates.children) {
                multiline.linestring(LineStringBuilder.class.cast(LINESTRING.getBuilder(node, radius, orientation, coerce)));
            }
            return multiline;
        }

        @Override
        CoordinateNode validate(CoordinateNode coordinates, boolean coerce) {
            if (coordinates.children.size() < 1) {
                throw new OpenSearchParseException("invalid number of lines in MultiLineString (found [{}] - must be >= 1)",
                    coordinates.children.size());
            }
            return coordinates;
        }
    },
    POLYGON("polygon") {
        @Override
        public PolygonBuilder getBuilder(CoordinateNode coordinates, DistanceUnit.Distance radius,
                                       Orientation orientation, boolean coerce) {
            validate(coordinates, coerce);
            // build shell
            LineStringBuilder shell = LineStringBuilder.class.cast(LINESTRING.getBuilder(coordinates.children.get(0),
                radius, orientation, coerce));
            // build polygon with shell and holes
            PolygonBuilder polygon = new PolygonBuilder(shell, orientation);
            for (int i = 1; i < coordinates.children.size(); ++i) {
                CoordinateNode child = coordinates.children.get(i);
                LineStringBuilder hole = LineStringBuilder.class.cast(LINESTRING.getBuilder(child, radius, orientation, coerce));
                polygon.hole(hole);
            }
            return polygon;
        }

        void validateLinearRing(CoordinateNode coordinates, boolean coerce) {
            if (coordinates.children == null || coordinates.children.isEmpty()) {
                String error = "Invalid LinearRing found.";
                error += (coordinates.coordinate == null) ?
                    " No coordinate array provided" : " Found a single coordinate when expecting a coordinate array";
                throw new OpenSearchParseException(error);
            }

            int numValidPts = coerce ? 3 : 4;
            if (coordinates.children.size() < numValidPts) {
                throw new OpenSearchParseException("invalid number of points in LinearRing (found [{}] - must be >= [{}])",
                    coordinates.children.size(), numValidPts);
            }
            // close linear ring iff coerce is set and ring is open, otherwise throw parse exception
            if (!coordinates.children.get(0).coordinate.equals(
                coordinates.children.get(coordinates.children.size() - 1).coordinate)) {
                if (coerce) {
                    coordinates.children.add(coordinates.children.get(0));
                } else {
                    throw new OpenSearchParseException("invalid LinearRing found (coordinates are not closed)");
                }
            }
        }

        @Override
        CoordinateNode validate(CoordinateNode coordinates, boolean coerce) {
            /**
             * Per GeoJSON spec (http://geojson.org/geojson-spec.html#linestring)
             * A LinearRing is closed LineString with 4 or more positions. The first and last positions
             * are equivalent (they represent equivalent points). Though a LinearRing is not explicitly
             * represented as a GeoJSON geometry type, it is referred to in the Polygon geometry type definition.
             */
            if (coordinates.children == null || coordinates.children.isEmpty()) {
                throw new OpenSearchParseException(
                    "invalid LinearRing provided for type polygon. Linear ring must be an array of coordinates");
            }
            for (CoordinateNode ring : coordinates.children) {
                validateLinearRing(ring, coerce);
            }

            return coordinates;
        }
    },
    MULTIPOLYGON("multipolygon") {
        @Override
        public MultiPolygonBuilder getBuilder(CoordinateNode coordinates, DistanceUnit.Distance radius,
                                       Orientation orientation, boolean coerce) {
            validate(coordinates, coerce);
            MultiPolygonBuilder polygons = new MultiPolygonBuilder(orientation);
            for (CoordinateNode node : coordinates.children) {
                polygons.polygon(PolygonBuilder.class.cast(POLYGON.getBuilder(node, radius, orientation, coerce)));
            }
            return polygons;
        }

        @Override
        CoordinateNode validate(CoordinateNode coordinates, boolean coerce) {
            // noop; todo validate at least 1 polygon to ensure valid multipolygon
            return coordinates;
        }
    },
    ENVELOPE("envelope") {
        @Override
        public EnvelopeBuilder getBuilder(CoordinateNode coordinates, DistanceUnit.Distance radius,
                                       Orientation orientation, boolean coerce) {
            validate(coordinates, coerce);
            // verify coordinate bounds, correct if necessary
            Coordinate uL = coordinates.children.get(0).coordinate;
            Coordinate lR = coordinates.children.get(1).coordinate;
            return new EnvelopeBuilder(uL, lR);
        }

        @Override
        CoordinateNode validate(CoordinateNode coordinates, boolean coerce) {
            // validate the coordinate array for envelope type
            if (coordinates.children.size() != 2) {
                throw new OpenSearchParseException(
                    "invalid number of points [{}] provided for geo_shape [{}] when expecting an array of 2 coordinates",
                    coordinates.children.size(), GeoShapeType.ENVELOPE.shapename);
            }
            return coordinates;
        }

        @Override
        public String wktName() {
            return BBOX;
        }
    },
    CIRCLE("circle") {
        @Override
        public CircleBuilder getBuilder(CoordinateNode coordinates, DistanceUnit.Distance radius,
                                       Orientation orientation, boolean coerce) {
            return new CircleBuilder().center(coordinates.coordinate).radius(radius);

        }

        @Override
        CoordinateNode validate(CoordinateNode coordinates, boolean coerce) {
            // noop
            return coordinates;
        }
    },
    GEOMETRYCOLLECTION("geometrycollection") {
        @Override
        public ShapeBuilder<?, ?, ?> getBuilder(CoordinateNode coordinates, DistanceUnit.Distance radius,
                                       Orientation orientation, boolean coerce) {
            // noop, handled in parser
            return null;
        }

        @Override
        CoordinateNode validate(CoordinateNode coordinates, boolean coerce) {
            // noop
            return null;
        }
    };

    private final String shapename;
    private static Map<String, GeoShapeType> shapeTypeMap = new HashMap<>();
    private static final String BBOX = "BBOX";

    static {
        for (GeoShapeType type : values()) {
            shapeTypeMap.put(type.shapename, type);
        }
        shapeTypeMap.put(ENVELOPE.wktName().toLowerCase(Locale.ROOT), ENVELOPE);
    }

    GeoShapeType(String shapename) {
        this.shapename = shapename;
    }

    public String shapeName() {
        return shapename;
    }

    public static GeoShapeType forName(String geoshapename) {
        String typename = geoshapename.toLowerCase(Locale.ROOT);
        if (shapeTypeMap.containsKey(typename)) {
            return shapeTypeMap.get(typename);
        }
        throw new IllegalArgumentException("unknown geo_shape ["+geoshapename+"]");
    }

    public abstract ShapeBuilder<?, ?, ?> getBuilder(CoordinateNode coordinates, DistanceUnit.Distance radius,
                                            ShapeBuilder.Orientation orientation, boolean coerce);
    abstract CoordinateNode validate(CoordinateNode coordinates, boolean coerce);

    /** wkt shape name */
    public String wktName() {
        return this.shapename;
    }

    public static List<Entry> getShapeWriteables() {
        List<Entry> namedWriteables = new ArrayList<>();
        namedWriteables.add(new Entry(ShapeBuilder.class, PointBuilder.TYPE.shapeName(), PointBuilder::new));
        namedWriteables.add(new Entry(ShapeBuilder.class, CircleBuilder.TYPE.shapeName(), CircleBuilder::new));
        namedWriteables.add(new Entry(ShapeBuilder.class, EnvelopeBuilder.TYPE.shapeName(), EnvelopeBuilder::new));
        namedWriteables.add(new Entry(ShapeBuilder.class, MultiPointBuilder.TYPE.shapeName(), MultiPointBuilder::new));
        namedWriteables.add(new Entry(ShapeBuilder.class, LineStringBuilder.TYPE.shapeName(), LineStringBuilder::new));
        namedWriteables.add(new Entry(ShapeBuilder.class, MultiLineStringBuilder.TYPE.shapeName(), MultiLineStringBuilder::new));
        namedWriteables.add(new Entry(ShapeBuilder.class, PolygonBuilder.TYPE.shapeName(), PolygonBuilder::new));
        namedWriteables.add(new Entry(ShapeBuilder.class, MultiPolygonBuilder.TYPE.shapeName(), MultiPolygonBuilder::new));
        namedWriteables.add(new Entry(ShapeBuilder.class, GeometryCollectionBuilder.TYPE.shapeName(), GeometryCollectionBuilder::new));
        return namedWriteables;
    }

    @Override
    public String toString() {
        return this.shapename;
    }
}
