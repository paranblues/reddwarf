/*
 * Copyright 2007-2009 Sun Microsystems, Inc.
 *
 * This file is part of Project Darkstar Server.
 *
 * Project Darkstar Server is free software: you can redistribute it
 * and/or modify it under the terms of the GNU General Public License
 * version 2 as published by the Free Software Foundation and
 * distributed hereunder to you.
 *
 * Project Darkstar Server is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.sun.sgs.impl.kernel;

import com.sun.sgs.app.TransactionAbortedException;
import com.sun.sgs.app.TransactionConflictException;
import com.sun.sgs.app.TransactionTimeoutException;
import com.sun.sgs.impl.profile.ProfileCollectorHandle;
import com.sun.sgs.impl.service.transaction.TransactionCoordinator;
import com.sun.sgs.impl.service.transaction.TransactionCoordinatorImpl;
import com.sun.sgs.impl.sharedutil.LoggerWrapper;
import static com.sun.sgs.impl.sharedutil.Objects.checkNull;
import com.sun.sgs.impl.sharedutil.PropertiesWrapper;
import com.sun.sgs.impl.util.lock.LockConflict;
import com.sun.sgs.impl.util.lock.LockConflictType;
import com.sun.sgs.impl.util.lock.LockManager;
import com.sun.sgs.impl.util.lock.LockRequest;
import com.sun.sgs.impl.util.lock.TxnLockManager;
import com.sun.sgs.impl.util.lock.TxnLocker;
import com.sun.sgs.kernel.AccessCoordinator;
import com.sun.sgs.kernel.AccessReporter;
import com.sun.sgs.kernel.AccessReporter.AccessType;
import com.sun.sgs.kernel.AccessedObject;
import com.sun.sgs.profile.AccessedObjectsDetail;
import com.sun.sgs.profile.AccessedObjectsDetail.ConflictType;
import com.sun.sgs.service.Transaction;
import com.sun.sgs.service.TransactionInterruptedException;
import com.sun.sgs.service.TransactionListener;
import com.sun.sgs.service.TransactionProxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * An implementation of {@link AccessCoordinator} that uses locking to handle
 * conflicts. <p>
 *
 * This implementation checks for deadlock whenever an access request is
 * blocked due to a conflict.  It selects the youngest transaction as the
 * deadlock victim, determining the age using the originally requested start
 * time for the task associated with the transaction.  The implementation does
 * not deny requests that would not result in deadlock.  When requests block,
 * it services the requests in the order that they arrive, except for upgrade
 * requests, which it puts ahead of non-upgrade requests.  The justification
 * for special treatment of upgrade requests is that an upgrade request is
 * useless if a conflicting request goes first and causes the waiter to lose
 * its read lock. <p>
 *
 * The methods that this class provides to implement {@code AccessReporter} are
 * not thread safe, and should either be called from a single thread or else
 * protected with external synchronization. <p>
 *
 * The {@link #LockingAccessCoordinator constructor} supports the following
 * configuration properties: <p>
 *
 * <dl style="margin-left: 1em">
 *
 * <dt> <i>Property:</i> <b>{@value #LOCK_TIMEOUT_PROPERTY}</b> <br>
 *	<i>Default:</i> {@value #DEFAULT_LOCK_TIMEOUT_PROPORTION} times the
 *	value of the {@code com.sun.sgs.txn.timeout} property, if specified,
 *	otherwise times the value of the default transaction timeout.
 *
 * <dd style="padding-top: .5em">The maximum number of milliseconds to wait for
 *	obtaining a lock.  The value must be greater than {@code 0}, and should
 *	be less than the transaction timeout. <p>
 *
 * <dt> <i>Property:</i> <b>{@value #NUM_KEY_MAPS_PROPERTY}</b> <br>
 *	<i>Default:</i> {@value #NUM_KEY_MAPS_DEFAULT}
 *
 * <dd style="padding-top: .5em">The number of maps to use for associating keys
 *	and maps.  The number of maps controls the amount of concurrency, and
 *	should typically be set to a value to support concurrent access by the
 *	number of active threads.  The value must be greater than {@code
 *	0}. <p>
 *
 * </dl> <p>
 *
 * This class uses the {@link Logger} named {@code
 * com.sun.sgs.impl.kernel.LockingAccessCoordinator} to log information at the
 * following logging levels: <p>
 *
 * <ul>
 * <li> {@link Level#FINER FINER} - Beginning and ending transactions
 * </ul>
 */
public class LockingAccessCoordinator extends AbstractAccessCoordinator {

    /** The class name. */
    private static final String CLASS =
	"com.sun.sgs.impl.kernel.LockingAccessCoordinator";

    /**
     * The property for specifying the maximum number of milliseconds to wait
     * for obtaining a lock.
     */
    public static final String LOCK_TIMEOUT_PROPERTY =
	CLASS + ".lock.timeout";

    /**
     * The proportion of the transaction timeout to use for the lock timeout if
     * no lock timeout is specified.
     */
    public static final double DEFAULT_LOCK_TIMEOUT_PROPORTION = 0.1;

    /**
     * The default value of the lock timeout property, if no transaction
     * timeout is specified.
     */
    public static final long DEFAULT_LOCK_TIMEOUT = 
	computeLockTimeout(TransactionCoordinatorImpl.BOUNDED_TIMEOUT_DEFAULT);

    /**
     * The property for specifying the number of maps to use for associating
     * keys and maps.  The number of maps controls the amount of concurrency.
     */
    public static final String NUM_KEY_MAPS_PROPERTY =
	CLASS + ".num.key.maps";

    /** The default number of key maps. */
    public static final int NUM_KEY_MAPS_DEFAULT = 8;

    /** The logger for this class. */
    static final LoggerWrapper logger = new LoggerWrapper(
	Logger.getLogger(LockingAccessCoordinator.class.getName()));

    /** Maps transactions to lockers. */
    private final ConcurrentMap<Transaction, LockerImpl> txnMap =
	new ConcurrentHashMap<Transaction, LockerImpl>();

    /** The lock manager. */
    private final TxnLockManager<Key, LockerImpl> lockManager;

    /* -- Public constructor -- */

    /**
     * Creates an instance of this class.
     *
     * @param	properties the configuration properties
     * @param	txnProxy the transaction proxy
     * @param	profileCollectorHandle the profile collector handle
     * @throws	IllegalArgumentException if the values of the configuration
     *		properties are illegal
     */
    public LockingAccessCoordinator(
	Properties properties,
	TransactionProxy txnProxy,
	ProfileCollectorHandle profileCollectorHandle)
    {
	super(txnProxy, profileCollectorHandle);
	PropertiesWrapper wrappedProps = new PropertiesWrapper(properties);
	long txnTimeout = wrappedProps.getLongProperty(
	    TransactionCoordinator.TXN_TIMEOUT_PROPERTY, -1);
	long defaultLockTimeout = (txnTimeout < 1)
	    ? DEFAULT_LOCK_TIMEOUT : computeLockTimeout(txnTimeout);
	long lockTimeout = wrappedProps.getLongProperty(
	    LOCK_TIMEOUT_PROPERTY, defaultLockTimeout, 1, Long.MAX_VALUE);
	int numKeyMaps = wrappedProps.getIntProperty(
	    NUM_KEY_MAPS_PROPERTY, NUM_KEY_MAPS_DEFAULT, 1, Integer.MAX_VALUE);
	lockManager =
	    new TxnLockManager<Key, LockerImpl>(lockTimeout, numKeyMaps);
    }

    /* -- Implement AccessCoordinator -- */

    /** {@inheritDoc} */
    public <T> AccessReporter<T> registerAccessSource(
	String sourceName, Class<T> objectIdType)
    {
	checkNull("objectIdType", objectIdType);
	return new AccessReporterImpl<T>(sourceName);
    }

    /**
     * {@inheritDoc} <p>
     *
     * This implementation does not record information about completed
     * transactions, so it always returns {@code null}.
     */
    public Transaction getConflictingTransaction(Transaction txn) {
	checkNull("txn", txn);
	return null;
    }

    /* -- Implement AccessCoordinatorHandle -- */

    /** {@inheritDoc} */
    public void notifyNewTransaction(
	Transaction txn, long requestedStartTime, int tryCount)
    {
	if (tryCount < 1) {
	    throw new IllegalArgumentException(
		"The tryCount must not be less than 1");
	}
	LockerImpl locker =
	    new LockerImpl(lockManager, txn, requestedStartTime);
	LockerImpl existing = txnMap.putIfAbsent(txn, locker);
	if (existing != null) {
	    throw new IllegalStateException("Transaction already started");
	}
	if (logger.isLoggable(Level.FINER)) {
	    logger.log(Level.FINER,
		       "begin {0}, requestedStartTime:{1,number,#}",
		       locker, requestedStartTime);
	}
	txn.registerListener(new TxnListener(txn));
    }

    /* -- Other public methods -- */

    /**
     * Attempts to acquire a lock, waiting if needed.  Returns information
     * about conflicts that occurred while attempting to acquire the lock that
     * prevented the lock from being acquired, or else {@code null} if the lock
     * was acquired.  If the {@code type} field of the return value is {@link
     * LockConflictType#DEADLOCK DEADLOCK}, then the caller should abort the
     * transaction, and any subsequent lock or wait requests will throw {@code
     * IllegalStateException}.
     *
     * @param	txn the transaction requesting the lock
     * @param	source the source of the object
     * @param	objectId the ID of the object
     * @param	forWrite whether to request a write lock
     * @param	description a description of the object being accessed, or
     *		{@code null}
     * @return	lock conflict information, or {@code null} if there was no
     *		conflict
     * @throws	IllegalArgumentException if {@link #notifyNewTransaction
     *		notifyNewTransaction} has not been called for {@code txn}
     * @throws	IllegalStateException if an earlier lock attempt for this
     *		transaction produced a deadlock, or if still waiting for an
     *		earlier attempt to complete
     */
    public LockConflict<Key, LockerImpl> lock(Transaction txn,
					      String source,
					      Object objectId,
					      boolean forWrite,
					      Object description)
    {
	LockerImpl locker = getLocker(txn);
	Key key = new Key(source, objectId);
	if (description != null) {
	    locker.setDescription(key, description);
	}
	return lockManager.lock(locker, key, forWrite);
    }

    /**
     * Attempts to acquire a lock, returning immediately.  Returns information
     * about any conflict that occurred while attempting to acquire the lock,
     * or else {@code null} if the lock was acquired.  If the attempt to
     * acquire the lock was blocked, returns a value with a {@code type} field
     * of {@link LockConflictType#BLOCKED BLOCKED} rather than waiting.  If the
     * {@code type} field of the return value is {@link
     * LockConflictType#DEADLOCK DEADLOCK}, then the caller should abort the
     * transaction, and any subsequent lock or wait requests will throw {@code
     * IllegalStateException}.
     *
     * @param	txn the transaction requesting the lock
     * @param	source the source of the object
     * @param	objectId the ID of the object
     * @param	forWrite whether to request a write lock
     * @param	description a description of the object being accessed, or
     *		{@code null}
     * @return	lock conflict information, or {@code null} if there was no
     *		conflict
     * @throws	IllegalArgumentException if {@link #notifyNewTransaction
     *		notifyNewTransaction} has not been called for {@code txn}
     * @throws	IllegalStateException if an earlier lock attempt for this
     *		transaction produced a deadlock, or if still waiting for an
     *		earlier attempt to complete
     */
    public LockConflict<Key, LockerImpl> lockNoWait(Transaction txn,
						    String source,
						    Object objectId,
						    boolean forWrite,
						    Object description)
    {
	LockerImpl locker = getLocker(txn);
	Key key = new Key(source, objectId);
	if (description != null) {
	    locker.setDescription(key, description);
	}
	return lockManager.lockNoWait(locker, key, forWrite);
    }

    /**
     * Waits for a previous attempt to obtain a lock that blocked.  Returns
     * information about any conflict that occurred while attempting to acquire
     * the lock, or else {@code null} if the lock was acquired or the
     * transaction was not waiting.  If the {@code type} field of the return
     * value is {@link LockConflictType#DEADLOCK DEADLOCK}, then the caller
     * should abort the transaction, and any subsequent lock or wait requests
     * will throw {@code IllegalStateException}.
     *
     * @param	txn the transaction requesting the lock
     * @return	lock conflict information, or {@code null} if there was no
     *		conflict
     * @throws	IllegalArgumentException if {@link #notifyNewTransaction
     *		notifyNewTransaction} has not been called for {@code txn}
     * @throws	IllegalStateException if an earlier lock attempt for this
     *		transaction produced a deadlock
     */
    public LockConflict<Key, LockerImpl> waitForLock(Transaction txn) {
	return lockManager.waitForLock(getLocker(txn));
    }

    /* -- Other methods -- */

    /**
     * Returns the locker associated with a transaction.
     *
     * @param	txn the transaction
     * @return	the locker
     * @throws	IllegalArgumentException if the transaction is not active
     */
    LockerImpl getLocker(Transaction txn) {
	checkNull("txn", txn);
	LockerImpl locker = txnMap.get(txn);
	if (locker == null) {
	    throw new IllegalArgumentException(
		"Transaction not active: " + txn);
	}
	return locker;
    }

    /**
     * Releases the locks for the transaction and reports object accesses to
     * the profiling system.
     *
     * @param	txn the finished transaction
     */
    private void endTransaction(Transaction txn) {
	LockerImpl locker = getLocker(txn);
	logger.log(Level.FINER, "end {0}", locker);
	locker.releaseAll();
	txnMap.remove(txn);
	profileCollectorHandle.setAccessedObjectsDetail(locker);
    }

    /**
     * Computes the lock timeout based on the specified transaction timeout and
     * {@link #DEFAULT_LOCK_TIMEOUT_PROPORTION}.
     */
    private static long computeLockTimeout(long txnTimeout) {
	long result = (long) (txnTimeout * DEFAULT_LOCK_TIMEOUT_PROPORTION);
	/* Lock timeout should be at least 1 */
	if (result < 1) {
	    result = 1;
	}
	return result;
    }

    /* -- Other classes -- */

    /**
     * Define a locker that records information about the transaction
     * requesting locks, and descriptions.
     */
    public static class LockerImpl extends TxnLocker<Key, LockerImpl>
	implements AccessedObjectsDetail
    {
	/** The associated transaction. */
	private final Transaction txn;

	/** The lock requests made by this transaction. */
	private final List<AccessedObjectImpl> requests =
	    new ArrayList<AccessedObjectImpl>();

	/** A map from keys to descriptions, or {@code null}. */
	private Map<Key, Object> keyToDescriptionMap = null;

	/**
	 * Creates an instance of this class.
	 *
	 * @param	txn the associated transaction
	 * @param	requestedStartTime the time milliseconds that the task
	 *		associated with the transaction was originally
	 *		requested to start
	 * @throws	IllegalArgumentException if {@code requestedStartTime}
	 *		is less than {@code 0}
	 */
	LockerImpl(TxnLockManager<Key, LockerImpl> lockManager,
		   Transaction txn,
		   long requestedStartTime)
	{
	    super(lockManager, requestedStartTime);
	    checkNull("txn", txn);
	    this.txn = txn;
	}

	/**
	 * {@inheritDoc} <p>
	 *
	 * This implementation stops the lock attempt when the transaction
	 * ends.
	 */
	@Override
	protected long getLockTimeoutTime(long now, long lockTimeoutTime) {
	    return Math.min(
		TxnLockManager.addCheckOverflow(now, lockTimeoutTime),
		TxnLockManager.addCheckOverflow(
		    txn.getCreationTime(), txn.getTimeout()));
	}

	/**
	 * {@inheritDoc} <p>
	 *
	 * This implementation records the new request and uses a local
	 * class.
	 */
	@Override
	protected LockRequest<Key, LockerImpl> newLockRequest(
	    Key key, boolean forWrite, boolean upgrade)
	{
	    AccessedObjectImpl request =
		new AccessedObjectImpl(this, key, forWrite, upgrade);
	    requests.add(request);
	    return request;
	}

	/** Release all locks. */
	void releaseAll() {
	    LockManager<Key, LockerImpl> lockManager = getLockManager();
	    for (LockRequest<Key, LockerImpl> request : requests) {
		lockManager.releaseLock(this, request.getKey());
	    }
	}

	/**
	 * Returns a string representation of this object.  This implementation
	 * prints the associated transaction, for debugging.
	 *
	 * @return	a string representation of this object
	 */
	@Override
	public String toString() {
	    return txn.toString();
	}

	/**
	 * Returns the transaction associated with this request.
	 *
	 * @return	the transaction associated with this request
	 */
	public Transaction getTransaction() {
	    return txn;
	}

	/* -- Implement AccessedObjectsDetail -- */

	/** {@inheritDoc} */
	public List<? extends AccessedObject> getAccessedObjects() {
	    return Collections.unmodifiableList(requests);
	}

	/** {@inheritDoc} */
	public ConflictType getConflictType() {
	    LockConflict<Key, LockerImpl> conflict = getConflict();
	    if (conflict == null) {
		return ConflictType.NONE;
	    } else if (conflict.getType() == LockConflictType.DEADLOCK) {
		return ConflictType.DEADLOCK;
	    } else {
		return ConflictType.ACCESS_NOT_GRANTED;
	    }
	}

	/** {@inheritDoc} */
	public byte[] getConflictingId() {
	    LockConflict<Key, LockerImpl> conflict = getConflict();
	    return (conflict != null)
		? conflict.getConflictingLocker().getTransaction().getId()
		: null;
	}

	/* -- Other methods -- */

	/**
	 * Sets the description associated with a key for this locker.  The
	 * description should not be {@code null}.  Does not replace an
	 * existing description.
	 *
	 * @param	key the key
	 * @param	description the description
	 */
	void setDescription(Key key, Object description) {
	    assert key != null;
	    assert description != null;
	    if (keyToDescriptionMap == null) {
		keyToDescriptionMap = new HashMap<Key, Object>();
	    }
	    if (!keyToDescriptionMap.containsKey(key)) {
		keyToDescriptionMap.put(key, description);
	    }
	}

	/**
	 * Gets the description associated with a key for this locker.
	 *
	 * @param	key the key
	 * @return	the description or {@code null}
	 */
	Object getDescription(Key key) {
	    return (keyToDescriptionMap == null)
		? null : keyToDescriptionMap.get(key);
	}
    }

    /** Implement {@code AccessedObject}. */
    private static class AccessedObjectImpl
	extends LockRequest<Key, LockerImpl>
	implements AccessedObject
    {
	/**
	 * Creates an instance of this class.
	 *
	 * @param	lockRequest the underlying lock request
	 */
	AccessedObjectImpl(LockerImpl locker,
			   Key key,
			   boolean forWrite,
			   boolean upgrade)
	{
	    super(locker, key, forWrite, upgrade);
	}

	/* -- Implement AccessedObject -- */

	/** {@inheritDoc} */
	public String getSource() {
	    return getKey().source;
	}

	/** {@inheritDoc} */
	public Object getObjectId() {
	    return getKey().objectId;
	}

	/** {@inheritDoc} */
	public AccessType getAccessType() {
	    return getForWrite() ? AccessType.WRITE : AccessType.READ;
	}

	/** {@inheritDoc} */
	public Object getDescription() {
	    return getLocker().getDescription(getKey());
	}

	/**
	 * Two instances are equal if they are instances of this class, and
	 * have the same source, object ID, and access type.
	 *
	 * @param	object the object to compare with
	 * @return	whether this instance equals the argument
	 */
	@Override
	public boolean equals(Object object) {
	    if (object == this) {
		return true;
	    } else if (object instanceof AccessedObjectImpl) {
		AccessedObjectImpl request = (AccessedObjectImpl) object;
		return getKey().equals(request.getKey()) &&
		    getForWrite() == request.getForWrite();
	    } else {
		return false;
	    }
	}

	@Override
	public int hashCode() {
	    return getKey().hashCode() ^ (getForWrite() ? 1 : 0);
	}

	/* -- Other methods -- */

	/** Print fields, for debugging. */
	@Override
	public String toString() {
	    return "AccessedObjectImpl[" + getLocker() + ", " +
		getKey() + ", " +
		(getForWrite() ? "WRITE" : getUpgrade() ? "UPGRADE" : "READ") +
		"]";
	}
    }

    /** Represents an object as identified by a source and an object ID. */
    private static final class Key {

	/** The source. */
	final String source;

	/** The object ID. */
	final Object objectId;

	/**
	 * Creates an instance of this class.
	 *
	 * @param	source the source of the object
	 * @param	objectId the object ID of the object
	 */
	Key(String source, Object objectId) {
	    checkNull("source", source);
	    checkNull("objectId", objectId);
	    this.source = source;
	    this.objectId = objectId;
	}

	/* -- Compare source and object ID -- */

	@Override
	public boolean equals(Object object) {
	    if (object == this) {
		return true;
	    } else if (object instanceof Key) {
		Key key = (Key) object;
		return source.equals(key.source) &&
		    objectId.equals(key.objectId);
	    } else {
		return false;
	    }
	}

	@Override
	public int hashCode() {
	    return source.hashCode() ^ objectId.hashCode();
	}

	/** Print fields, for debugging. */
	@Override
	public String toString() {
	    return source + ":" + objectId;
	}
    }

    /** Implement {@link AccessReporter}. */
    private class AccessReporterImpl<T> extends AbstractAccessReporter<T> {

	/**
	 * Creates an instance of this class.
	 *
	 * @param	source the source of the objects managed by this
	 *		reporter
	 */
	AccessReporterImpl(String source) {
	    super(source);
	}

	/* -- Implement AccessReporter -- */

	/** {@inheritDoc} */
	public void reportObjectAccess(
	    Transaction txn, T objectId, AccessType type, Object description)
	{
	    checkNull("type", type);
	    LockConflict<Key, LockerImpl> conflict = lock(
		txn, source, objectId, type == AccessType.WRITE, description);
	    if (conflict != null) {
		String descriptionMsg = "";
		if (description != null) {
		    try {
			descriptionMsg = ", description:" + description;
		    } catch (RuntimeException e) {
		    }
		}
		String accessMsg = "Access txn:" + txn +
		    ", type:" + type +
		    ", source:" + source +
		    ", objectId:" + objectId +
		    descriptionMsg +
		    " failed: ";
		String conflictMsg = ", with conflicting transaction " +
		    conflict.getConflictingLocker().getTransaction();
		TransactionAbortedException exception;
		switch (conflict.getType()) {
		case TIMEOUT:
		    exception = new TransactionTimeoutException(
			accessMsg + "Transaction timed out" + conflictMsg);
		    break;
		case DENIED:
		    exception = new TransactionConflictException(
			accessMsg + "Access denied" + conflictMsg);
		    break;
		case INTERRUPTED:
		    exception = new TransactionInterruptedException(
			accessMsg + "Transaction interrupted" + conflictMsg);
		    break;
		case DEADLOCK:
		    exception = new TransactionConflictException(
			accessMsg + "Transaction deadlock" + conflictMsg);
		    break;
		default:
		    throw new AssertionError(
			"Should not be " + conflict.getType());
		}
		txn.abort(exception);
		throw exception;
	    }
	}

	/** {@inheritDoc} */
	public void setObjectDescription(
	    Transaction txn, T objectId, Object description)
	{
	    LockerImpl locker = getLocker(txn);
	    if (description == null) {
		checkNull("objectId", objectId);
	    } else {
		locker.setDescription(new Key(source, objectId), description);
	    }
	}
    }

    /**
     * A transaction listener that calls {@link #endTransaction} when called
     * after the transaction completes.  Use a listener instead of a
     * transaction participant to make sure that locks are released only after
     * all of the transaction participants have finished their work.
     */
    private class TxnListener implements TransactionListener {

	/** The transaction. */
	private final Transaction txn;

	/**
	 * Creates an instance of this class.
	 *
	 * @param	txn the transaction we're listening for
	 */
	TxnListener(Transaction txn) {
	    this.txn = txn;
	}

	/**
	 * {@inheritDoc} <p>
	 *
	 * This implementation does nothing.
	 */
	public void beforeCompletion() { }

	/**
	 * {@inheritDoc} <p>
	 *
	 * This implementation calls {@link #endTransaction}.
	 */
	public void afterCompletion(boolean committed) {
	    endTransaction(txn);
	}
    }
}