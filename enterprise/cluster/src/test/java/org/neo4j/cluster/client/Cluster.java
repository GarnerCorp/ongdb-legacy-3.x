/*
 * Copyright (c) 2002-2018 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j Enterprise Edition. The included source
 * code can be redistributed and/or modified under the terms of the
 * GNU AFFERO GENERAL PUBLIC LICENSE Version 3
 * (http://www.fsf.org/licensing/licenses/agpl-3.0.html) with the
 * Commons Clause, as found in the associated LICENSE.txt file.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * Neo4j object code can be licensed independently from the source
 * under separate terms from the AGPL. Inquiries can be directed to:
 * licensing@neo4j.com
 *
 * More information is also available at:
 * https://neo4j.com/licensing/
 */
package org.neo4j.cluster.client;

import org.apache.commons.lang3.RandomStringUtils;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nonnull;

import static java.util.Objects.requireNonNull;

public class Cluster
{
    private final String name;
    private final List<Member> members = new ArrayList<>();

    /**
     * Creates a cluster with a random name.
     * The name is used in deciding which directory the store files are placed in.
     */
    public Cluster()
    {
        this( RandomStringUtils.randomAlphanumeric( 8 ) );
    }

    /**
     * Creates a cluster with a specified name.
     * The name is used in deciding which directory the store files are placed in.
     */
    public Cluster( @Nonnull String name )
    {
        requireNonNull( name );
        this.name = name;
    }

    public String getName()
    {
        return name;
    }

    public List<Member> getMembers()
    {
        return members;
    }

    @Override
    public boolean equals( Object o )
    {
        if ( this == o )
        {
            return true;
        }
        if ( o == null || getClass() != o.getClass() )
        {
            return false;
        }

        Cluster cluster = (Cluster) o;

        return name.equals( cluster.name ) && members.equals( cluster.members );

    }

    @Override
    public int hashCode()
    {
        int result = name.hashCode();
        result = 31 * result + members.hashCode();
        return result;
    }

    /**
     * Represents a member tag in the discovery XML file.
     */
    public static class Member
    {
        private final String host;
        private final boolean fullHaMember;

        public Member( int port, boolean fullHaMember )
        {
            this( localhost() + ":" + port, fullHaMember );
        }

        public Member( String host, boolean fullHaMember )
        {
            this.host = host;
            this.fullHaMember = fullHaMember;
        }

        public boolean isFullHaMember()
        {
            return fullHaMember;
        }

        private static String localhost()
        {
            return "localhost";
        }

        public String getHost()
        {
            return host;
        }

        @Override
        public boolean equals( Object o )
        {
            if ( this == o )
            {
                return true;
            }
            if ( o == null || getClass() != o.getClass() )
            {
                return false;
            }

            Member member = (Member) o;

            return host.equals( member.host );

        }

        @Override
        public int hashCode()
        {
            return host.hashCode();
        }
    }
}
