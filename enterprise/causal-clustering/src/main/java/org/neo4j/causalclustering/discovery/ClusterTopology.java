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
package org.neo4j.causalclustering.discovery;

import java.util.Optional;

import org.neo4j.causalclustering.identity.MemberId;

public class ClusterTopology
{
    private final CoreTopology coreTopology;
    private final ReadReplicaTopology readReplicaTopology;

    public ClusterTopology( CoreTopology coreTopology, ReadReplicaTopology readReplicaTopology )
    {
        this.coreTopology = coreTopology;
        this.readReplicaTopology = readReplicaTopology;
    }

    public Optional<CatchupServerAddress> find( MemberId upstream )
    {
        Optional<CatchupServerAddress> coreCatchupAddress = coreTopology.find( upstream ).map( a -> (CatchupServerAddress) a );
        Optional<CatchupServerAddress> readCatchupAddress = readReplicaTopology.find( upstream ).map( a -> (CatchupServerAddress) a );

        return coreCatchupAddress.map( Optional::of ).orElse( readCatchupAddress );
    }
}
