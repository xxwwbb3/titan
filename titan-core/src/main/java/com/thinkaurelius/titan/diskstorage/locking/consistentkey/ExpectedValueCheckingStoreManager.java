package com.thinkaurelius.titan.diskstorage.locking.consistentkey;

import com.thinkaurelius.titan.core.attribute.Duration;
import com.thinkaurelius.titan.diskstorage.BackendException;
import com.thinkaurelius.titan.diskstorage.StaticBuffer;
import com.thinkaurelius.titan.diskstorage.BaseTransactionConfig;
import com.thinkaurelius.titan.diskstorage.configuration.Configuration;
import com.thinkaurelius.titan.diskstorage.configuration.MergedConfiguration;
import com.thinkaurelius.titan.diskstorage.keycolumnvalue.*;
import com.thinkaurelius.titan.diskstorage.locking.LockerProvider;
import com.thinkaurelius.titan.diskstorage.util.StandardBaseTransactionConfig;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Matthias Broecheler (me@matthiasb.com)
 */
public class ExpectedValueCheckingStoreManager implements KeyColumnValueStoreManager {

    private final KeyColumnValueStoreManager storeManager;
    private final String lockStoreSuffix;
    private final LockerProvider lockerProvider;
    private final Duration maxReadTime;
    private final StoreFeatures storeFeatures;

    private final Map<String,ExpectedValueCheckingStore> stores;

    private static final Logger log =
            LoggerFactory.getLogger(ExpectedValueCheckingStoreManager.class);

    public ExpectedValueCheckingStoreManager(KeyColumnValueStoreManager storeManager, String lockStoreSuffix,
                                             LockerProvider lockerProvider, Duration maxReadTime) {
        this.storeManager = storeManager;
        this.lockStoreSuffix = lockStoreSuffix;
        this.lockerProvider = lockerProvider;
        this.maxReadTime = maxReadTime;
        this.storeFeatures = storeManager.getFeatures();
        this.stores = new HashMap<String,ExpectedValueCheckingStore>(6);
    }

    @Override
    public synchronized KeyColumnValueStore openDatabase(String name) throws BackendException {
        if (stores.containsKey(name)) return stores.get(name);
        KeyColumnValueStore store = storeManager.openDatabase(name);
        final String lockerName = store.getName() + lockStoreSuffix;
        ExpectedValueCheckingStore wrappedStore = new ExpectedValueCheckingStore(store, lockerProvider.getLocker(lockerName));
        stores.put(name,wrappedStore);
        return wrappedStore;
    }

    @Override
    public void mutateMany(Map<String, Map<StaticBuffer, KCVMutation>> mutations, StoreTransaction txh) throws BackendException {
        ExpectedValueCheckingTransaction etx = (ExpectedValueCheckingTransaction)txh;
        etx.prepareForMutations();
        storeManager.mutateMany(mutations, etx.getDataTransaction());
    }

    @Override
    public ExpectedValueCheckingTransaction beginTransaction(BaseTransactionConfig configuration) throws BackendException {
        StoreTransaction tx = storeManager.beginTransaction(configuration);

        Configuration customOptions = new MergedConfiguration(storeFeatures.getKeyConsistentTxConfig(), configuration.getCustomOptions());
        BaseTransactionConfig consistentTxCfg = new StandardBaseTransactionConfig.Builder(configuration)
                .customOptions(customOptions)
                .build();
        StoreTransaction consistentTx = storeManager.beginTransaction(consistentTxCfg);
        ExpectedValueCheckingTransaction wrappedTx = new ExpectedValueCheckingTransaction(tx, consistentTx, maxReadTime);
        return wrappedTx;
    }

    @Override
    public void close() throws BackendException {
        storeManager.close();
    }

    @Override
    public void clearStorage() throws BackendException {
        storeManager.clearStorage();
    }

    @Override
    public List<KeyRange> getLocalKeyPartition() throws BackendException {
        return storeManager.getLocalKeyPartition();
    }

    @Override
    public StoreFeatures getFeatures() {
        StoreFeatures features = new StandardStoreFeatures.Builder(storeFeatures).locking(true).build();
        return features;
    }

    @Override
    public String getName() {
        return storeManager.getName();
    }
}
