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
package org.neo4j.causalclustering.core;

import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.neo4j.graphdb.config.Setting;
import org.neo4j.helpers.HostnamePort;
import org.neo4j.helpers.ListenSocketAddress;
import org.neo4j.kernel.configuration.Config;
import org.neo4j.kernel.configuration.Settings;

class HostnamePortAsListenAddress
{
    private static final Pattern portRange = Pattern.compile( "(:\\d+)(-\\d+)?$" );

    static ListenSocketAddress resolve( Config config, Setting<HostnamePort> setting )
    {
        Function<String,ListenSocketAddress> resolveFn = Settings.LISTEN_SOCKET_ADDRESS.compose( HostnamePortAsListenAddress::stripPortRange );
        return config.get( Settings.setting( setting.name(), resolveFn, setting.getDefaultValue() ) );
    }

    private static String stripPortRange( String address )
    {
        Matcher m = portRange.matcher( address );
        return m.find() ? m.replaceAll( "$1" ) : address;
    }

}
