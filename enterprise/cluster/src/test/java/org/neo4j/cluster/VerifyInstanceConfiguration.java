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
package org.neo4j.cluster;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class VerifyInstanceConfiguration
{
    public final List<URI> members;
    public final Map<String, InstanceId> roles;
    public final Set<InstanceId> failed;

    public VerifyInstanceConfiguration( List<URI> members, Map<String, InstanceId> roles, Set<InstanceId> failed )
    {
        this.members = members;
        this.roles = roles;
        this.failed = failed;
    }
}
