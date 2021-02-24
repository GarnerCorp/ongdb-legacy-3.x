/*
 * Copyright (c) 2002-2019 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.impl.coreapi.schema;

import java.util.ArrayList;

import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.schema.ConstraintDefinition;

public class NodePropertyUniqueConstraintCreator extends BaseNodeConstraintCreator
{
    protected final ArrayList<String> propertyKeys = new ArrayList<>();

    NodePropertyUniqueConstraintCreator( InternalSchemaActions internalCreator, Label label, String propertyKey )
    {
        super( internalCreator, label );
        this.propertyKeys.add( propertyKey );
    }

    @Override
    public final NodePropertyUniqueConstraintCreator assertPropertyIsUnique( String propertyKey )
    {
        throw new UnsupportedOperationException( "You can only create one unique constraint at a time." );
    }

    @Override
    public final ConstraintDefinition create()
    {
        assertInUnterminatedTransaction();

        IndexDefinitionImpl definition =
                new IndexDefinitionImpl( actions, null, new Label[]{label}, propertyKeys.toArray( new String[0] ), true );
        return actions.createPropertyUniquenessConstraint( definition );
    }
}
