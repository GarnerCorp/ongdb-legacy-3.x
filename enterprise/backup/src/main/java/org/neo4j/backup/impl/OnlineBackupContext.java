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
package org.neo4j.backup.impl;

import java.nio.file.Path;

import org.neo4j.consistency.checking.full.ConsistencyFlags;
import org.neo4j.kernel.configuration.Config;

class OnlineBackupContext
{
    private final OnlineBackupRequiredArguments requiredArguments;
    private final Config config;
    private final ConsistencyFlags consistencyFlags;

    OnlineBackupContext( OnlineBackupRequiredArguments requiredArguments, Config config, ConsistencyFlags consistencyFlags )
    {
        this.requiredArguments = requiredArguments;
        this.config = config;
        this.consistencyFlags = consistencyFlags;
    }

    public OnlineBackupRequiredArguments getRequiredArguments()
    {
        return requiredArguments;
    }

    public Config getConfig()
    {
        return config;
    }

    public ConsistencyFlags getConsistencyFlags()
    {
        return consistencyFlags;
    }

    public Path getResolvedLocationFromName()
    {
        return requiredArguments.getResolvedLocationFromName();
    }
}
