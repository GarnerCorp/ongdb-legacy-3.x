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
package org.neo4j.causalclustering.catchup.tx;

import org.neo4j.causalclustering.identity.StoreId;
import org.neo4j.kernel.impl.transaction.CommittedTransactionRepresentation;

public class TxPullResponse
{
    private final StoreId storeId;
    private final CommittedTransactionRepresentation tx;

    public TxPullResponse( StoreId storeId, CommittedTransactionRepresentation tx )
    {
        this.storeId = storeId;
        this.tx = tx;
    }

    public StoreId storeId()
    {
        return storeId;
    }

    public CommittedTransactionRepresentation tx()
    {
        return tx;
    }

    @Override
    public boolean equals( Object o )
    {
        if ( this == o )
        {
            return true;
        }
        if ( o == null || getClass() != o.getClass() )
        {
            return false;
        }

        TxPullResponse that = (TxPullResponse) o;

        return (storeId != null ? storeId.equals( that.storeId ) : that.storeId == null) &&
                (tx != null ? tx.equals( that.tx ) : that.tx == null);
    }

    @Override
    public int hashCode()
    {
        int result = storeId != null ? storeId.hashCode() : 0;
        result = 31 * result + (tx != null ? tx.hashCode() : 0);
        return result;
    }

    @Override
    public String toString()
    {
        return String.format( "TxPullResponse{storeId=%s, tx=%s}", storeId, tx );
    }
}
