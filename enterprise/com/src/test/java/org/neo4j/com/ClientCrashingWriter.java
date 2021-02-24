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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;

public class ClientCrashingWriter implements MadeUpWriter
{
    private final MadeUpClient client;
    private final int crashAtSize;
    private int totalSize;

    public ClientCrashingWriter( MadeUpClient client, int crashAtSize )
    {
        this.client = client;
        this.crashAtSize = crashAtSize;
    }

    @Override
    public void write( ReadableByteChannel data )
    {
        ByteBuffer buffer = ByteBuffer.allocate( 1000 );
        while ( true )
        {
            buffer.clear();
            try
            {
                int size = data.read( buffer );
                if ( size == -1 )
                {
                    break;
                }
                if ( (totalSize += size) >= crashAtSize )
                {
                    client.stop();
                    throw new IOException( "Fake read error" );
                }
            }
            catch ( IOException e )
            {
                throw new ComException( e );
            }
        }
    }

    public int getSizeRead()
    {
        return totalSize;
    }
}
