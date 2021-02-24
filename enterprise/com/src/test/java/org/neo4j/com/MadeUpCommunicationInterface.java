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

import java.nio.channels.ReadableByteChannel;

public interface MadeUpCommunicationInterface
{
    Response<Integer> multiply( int value1, int value2 );

    Response<Void> fetchDataStream( MadeUpWriter writer, int dataSize );

    Response<Void> sendDataStream( ReadableByteChannel data );

    Response<Integer> throwException( String messageInException );

    Response<Integer> streamBackTransactions( int responseToSendBack, int txCount );

    Response<Integer> informAboutTransactionObligations( int responseToSendBack, long desiredObligation );
}
