/*
 * Copyright (c) 2018-2020 "Graph Foundation"
 * Graph Foundation, Inc. [https://graphfoundation.org]
 *
 * Copyright (c) 2002-2018 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of ONgDB Enterprise Edition. The included source
 * code can be redistributed and/or modified under the terms of the
 * GNU AFFERO GENERAL PUBLIC LICENSE Version 3
 * (http://www.fsf.org/licensing/licenses/agpl-3.0.html) with the
 * Commons Clause, as found
 * in the associated LICENSE.txt file.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 */
package org.neo4j.com;

import java.util.Iterator;
import java.util.NoSuchElementException;

public class PortIterator implements Iterator<Integer>
{
    private final int start;
    private final int end;
    private int next;

    public PortIterator( int[] portRanges )
    {
        start = portRanges[0];
        end = portRanges[1];
        next = start;
    }

    @Override
    public boolean hasNext()
    {
        return start < end ? next <= end : next >= end;
    }

    @Override
    public Integer next()
    {
        if ( !hasNext() )
        {
            throw new NoSuchElementException();
        }
        return start < end ? next++ : next--;
    }

    @Override
    public void remove()
    {
        throw new UnsupportedOperationException();
    }
}
