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

import org.junit.Rule;

import java.util.Map;

import org.neo4j.graphdb.mockfs.UncloseableDelegatingFileSystemAbstraction;
import org.neo4j.kernel.impl.util.ValueUtils;
import org.neo4j.test.rule.fs.EphemeralFileSystemRule;

public class BoltAuthScenariosInteractionIT extends AuthScenariosInteractionTestBase<BoltInteraction.BoltSubject>
{
    @Rule
    public EphemeralFileSystemRule fileSystemRule = new EphemeralFileSystemRule();

    public BoltAuthScenariosInteractionIT()
    {
        super();
        IS_EMBEDDED = false;
    }

    @Override
    public NeoInteractionLevel<BoltInteraction.BoltSubject> setUpNeoServer( Map<String,String> config )
    {
        return new BoltInteraction( config,
                () -> new UncloseableDelegatingFileSystemAbstraction( fileSystemRule.get() ) );
    }

    @Override
    protected Object valueOf( Object obj )
    {
        return ValueUtils.of( obj );
    }
}
