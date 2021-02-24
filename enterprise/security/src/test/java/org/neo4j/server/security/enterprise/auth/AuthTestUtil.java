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
package org.neo4j.server.security.enterprise.auth;

import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

class AuthTestUtil
{
    private AuthTestUtil()
    {
    }

    public static <T> T[] with( Class<T> clazz, T[] things, T... moreThings )
    {
        return Stream.concat( Arrays.stream(things), Arrays.stream( moreThings ) ).toArray(
                size -> (T[]) Array.newInstance( clazz, size )
        );
    }

    public static String[] with( String[] things, String... moreThings )
    {
        return Stream.concat( Arrays.stream(things), Arrays.stream( moreThings ) ).toArray( String[]::new );
    }

    public static <T> List<T> listOf( T... values )
    {
        return Stream.of( values ).collect( Collectors.toList() );
    }

}
