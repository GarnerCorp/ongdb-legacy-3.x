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
package org.neo4j.kernel.impl.enterprise.transaction.log.checkpoint;

import java.time.Clock;
import org.neo4j.graphdb.factory.GraphDatabaseSettings;
import org.neo4j.helpers.Service;
import org.neo4j.kernel.configuration.Config;
import org.neo4j.kernel.impl.transaction.log.checkpoint.CheckPointThreshold;
import org.neo4j.kernel.impl.transaction.log.checkpoint.CheckPointThresholdPolicy;
import org.neo4j.kernel.impl.transaction.log.pruning.LogPruning;
import org.neo4j.logging.LogProvider;
import org.neo4j.time.SystemNanoClock;

import static org.neo4j.kernel.impl.transaction.log.checkpoint.CheckPointThreshold.or;

@Service.Implementation( CheckPointThresholdPolicy.class )
public class ContinuousThresholdPolicy extends CheckPointThresholdPolicy
{
    public ContinuousThresholdPolicy()
    {
        super( "continuous" );
    }

    @Override
    public CheckPointThreshold createThreshold(
            Config config, SystemNanoClock clock, LogPruning logPruning, LogProvider logProvider )
    {
        return new ContinuousCheckPointThreshold();
    }
}