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
package org.neo4j.kernel.ha;

import org.junit.Before;
import org.junit.Test;

import java.io.IOException;

import org.neo4j.kernel.impl.store.TransactionId;
import org.neo4j.kernel.impl.transaction.log.LogPosition;
import org.neo4j.kernel.impl.transaction.log.LogicalTransactionStore;
import org.neo4j.kernel.impl.transaction.log.TransactionIdStore;
import org.neo4j.kernel.impl.transaction.log.TransactionMetadataCache;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class TransactionChecksumLookupTest
{

    private TransactionIdStore transactionIdStore = mock( TransactionIdStore.class );
    private LogicalTransactionStore transactionStore = mock( LogicalTransactionStore.class );

    @Before
    public void setUp() throws IOException
    {
        when( transactionIdStore.getLastCommittedTransaction() ).thenReturn( new TransactionId( 1, 1, 1 ) );
        when( transactionIdStore.getUpgradeTransaction() ).thenReturn( new TransactionId( 2, 2, 2 ) );
        when( transactionStore.getMetadataFor( 3 ) ).thenReturn(
                new TransactionMetadataCache.TransactionMetadata( 1, 1, mock( LogPosition.class ), 3, 3 ) );
    }

    @Test
    public void lookupChecksumUsingUpgradeTransaction() throws Exception
    {
        TransactionChecksumLookup checksumLookup = new TransactionChecksumLookup( transactionIdStore, transactionStore );
        assertEquals(2, checksumLookup.lookup( 2 ));
    }

    @Test
    public void lookupChecksumUsingCommittedTransaction() throws Exception
    {
        TransactionChecksumLookup checksumLookup = new TransactionChecksumLookup( transactionIdStore, transactionStore );
        assertEquals(1, checksumLookup.lookup( 1 ));
    }

    @Test
    public void lookupChecksumUsingTransactionStore() throws Exception
    {
        TransactionChecksumLookup checksumLookup = new TransactionChecksumLookup( transactionIdStore, transactionStore );
        assertEquals(3, checksumLookup.lookup( 3 ));
    }
}
