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
package org.neo4j.server.arbiter;

import java.io.IOException;
import java.util.Collections;

import org.neo4j.server.ServerCommandLineArgs;
import org.neo4j.server.enterprise.ArbiterBootstrapper;

public class ArbiterBootstrapperTestProxy
{
    public static final String START_SIGNAL = "starting";

    private ArbiterBootstrapperTestProxy()
    {
    }

    public static void main( String[] argv ) throws IOException
    {
        ServerCommandLineArgs args = ServerCommandLineArgs.parse( argv );

        // This sysout will be intercepted by the parent process and will trigger
        // a start of a timeout. The whole reason for this class to be here is to
        // split awaiting for the process to start and actually awaiting the cluster client to start.
        System.out.println( START_SIGNAL );
        try ( ArbiterBootstrapper arbiter = new ArbiterBootstrapper() )
        {
            arbiter.start( args.homeDir(), args.configFile(), Collections.emptyMap() );
            System.in.read();
        }
    }
}
