package com.orientechnologies.orient.server.distributed.impl;

import com.orientechnologies.common.concur.lock.OLockException;
import com.orientechnologies.common.concur.lock.OSimpleLockManager;
import com.orientechnologies.common.exception.OException;
import com.orientechnologies.orient.core.db.ODatabaseDocumentInternal;
import com.orientechnologies.orient.core.id.ORID;
import com.orientechnologies.orient.core.id.ORecordId;
import com.orientechnologies.orient.core.tx.OTransactionId;
import com.orientechnologies.orient.core.tx.OTxMetadataHolder;
import com.orientechnologies.orient.core.tx.OTransactionInternal;
import com.orientechnologies.orient.server.distributed.*;
import com.orientechnologies.orient.server.distributed.task.ODistributedKeyLockedException;
import com.orientechnologies.orient.server.distributed.task.ODistributedRecordLockedException;
import com.orientechnologies.orient.server.distributed.task.ORemoteTask;

import java.io.IOException;
import java.util.*;

public class ONewDistributedTxContextImpl implements ODistributedTxContext {

  public enum Status {
    FAILED, SUCCESS, TIMEDOUT,
  }

  private final ODistributedDatabaseImpl shared;
  private final ODistributedRequestId    id;
  private final OTransactionInternal     tx;
  private final long                     startedOn;
  private final List<ORID>               lockedRids = new ArrayList<>();
  private final List<Object>             lockedKeys = new ArrayList<>();
  private       Status                   status;
  private final OTransactionId           transactionId;

  public ONewDistributedTxContextImpl(ODistributedDatabaseImpl shared, ODistributedRequestId reqId, OTransactionInternal tx,
      OTransactionId id) {
    this.shared = shared;
    this.id = reqId;
    this.tx = tx;
    this.startedOn = System.currentTimeMillis();
    transactionId = id;
  }

  @Override
  public void lockIndexKey(Object key) {
    OSimpleLockManager<Object> recordLockManager = shared.getIndexKeyLockManager();
    try {
      recordLockManager.lock(key);
    } catch (OLockException ex) {
      this.unlock();
      throw new ODistributedKeyLockedException(shared.getLocalNodeName(), key, recordLockManager.getTimeout());
    }
    lockedKeys.add(key);
  }

  @Override
  public void lock(ORID rid) {
    OSimpleLockManager<ORID> recordLockManager = shared.getRecordLockManager();
    try {
      recordLockManager.lock(rid);
    } catch (OLockException ex) {
      this.unlock();
      throw new ODistributedRecordLockedException(shared.getLocalNodeName(), rid, recordLockManager.getTimeout());
    }
    lockedRids.add(rid);
  }

  @Override
  public void lock(ORID rid, long timeout) {
    //TODO: the timeout is only in the lock manager, this implementation may need evolution
    OSimpleLockManager<ORID> recordLockManager = shared.getRecordLockManager();
    try {
      recordLockManager.lock(rid, timeout);
    } catch (OLockException ex) {
      this.unlock();
      throw new ODistributedRecordLockedException(shared.getLocalNodeName(), rid, timeout);
    }
    lockedRids.add(rid);
  }

  @Override
  public ODistributedRequestId getReqId() {
    return id;
  }

  @Override
  public synchronized void begin(ODatabaseDocumentInternal database, boolean local) {
    throw new UnsupportedOperationException();
  }

  @Override
  public synchronized void commit(ODatabaseDocumentInternal database) {
    ODistributedDatabase localDistributedDatabase = ((ODatabaseDocumentDistributed) database).getStorageDistributed()
        .getLocalDistributedDatabase();
    OTxMetadataHolder metadataHolder = localDistributedDatabase.commit(getTransactionId());
    try {
      tx.setMetadataHolder(Optional.of(metadataHolder));
      tx.prepareSerializedOperations();
      ((ODatabaseDocumentDistributed) database).internalCommit2pc(this);
    } catch (IOException e) {
      throw OException.wrapException(new ODistributedException("Error on preparation of log serialized operations"), e);
    } finally {
      metadataHolder.notifyMetadataRead();
    }
  }

  @Override
  public Set<ORecordId> rollback(ODatabaseDocumentInternal database) {
    return new HashSet<>();
  }

  @Override
  public void destroy() {
    unlock();
  }

  @Override
  public void clearUndo() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void unlock() {
    shared.rollback(this.transactionId);
    for (ORID lockedRid : lockedRids) {
      shared.getRecordLockManager().unlock(lockedRid);
    }
    lockedRids.clear();
    for (Object lockedKey : lockedKeys) {
      shared.getIndexKeyLockManager().unlock(lockedKey);
    }
    lockedKeys.clear();
  }

  @Override
  public long getStartedOn() {
    return startedOn;
  }

  @Override
  public Set<ORecordId> cancel(ODistributedServerManager current, ODatabaseDocumentInternal database) {
    destroy();
    return null;
  }

  @Override
  public OTransactionInternal getTransaction() {
    return tx;
  }

  public void setStatus(Status status) {
    this.status = status;
  }

  public Status getStatus() {
    return status;
  }

  public List<ORID> getLockedRids() {
    return lockedRids;
  }

  @Override
  public OTransactionId getTransactionId() {
    return transactionId;
  }
}
