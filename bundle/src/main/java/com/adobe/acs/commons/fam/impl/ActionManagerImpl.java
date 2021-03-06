/*
 * Copyright 2016 Adobe.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.adobe.acs.commons.fam.impl;

import com.adobe.acs.commons.fam.CancelHandler;
import com.adobe.acs.commons.fam.ActionManager;
import com.adobe.acs.commons.fam.Failure;
import com.adobe.acs.commons.fam.ThrottledTaskRunner;
import com.adobe.acs.commons.fam.actions.Actions;
import com.adobe.acs.commons.functions.*;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.query.Query;
import javax.jcr.query.QueryManager;
import javax.jcr.query.QueryResult;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.OpenDataException;
import javax.management.openmbean.OpenType;
import javax.management.openmbean.SimpleType;
import javax.management.openmbean.TabularType;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.ResourceResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages a pool of reusable resource resolvers and injects them into tasks
 */
class ActionManagerImpl extends CancelHandler implements ActionManager, Serializable {
    private static final long serialVersionUID = 7526472295622776150L;

    transient private static final Logger LOG = LoggerFactory.getLogger(ActionManagerImpl.class);
    // This is a delay of how long an action manager should wait before it can safely assume it really is done and no more work is being added
    // This helps prevent an action manager from closing itself down while the queue is warming up.
    transient public static final int HESITATION_DELAY = 50;
    // The cleanup task will wait this many milliseconds between its polling to see if the queue has been completely processed
    transient public static final int COMPLETION_CHECK_INTERVAL = 100;
    private final AtomicInteger tasksAdded = new AtomicInteger();
    private final AtomicInteger tasksCompleted = new AtomicInteger();
    private final AtomicInteger tasksFilteredOut = new AtomicInteger();
    private final AtomicInteger tasksSuccessful = new AtomicInteger();
    private final AtomicInteger tasksError = new AtomicInteger();
    private final String name;
    private final AtomicLong started = new AtomicLong(0);
    private long finished;
    private int saveInterval;

    transient private final ResourceResolver baseResolver;
    transient private final List<ReusableResolver> resolvers = Collections.synchronizedList(new ArrayList<>());
    transient private final ThreadLocal<ReusableResolver> currentResolver = new ThreadLocal<>();
    transient private final ThrottledTaskRunner taskRunner;
    transient private final ThreadLocal<String> currentPath;
    private final List<Failure> failures;
    transient private final AtomicBoolean cleanupHandlerRegistered = new AtomicBoolean(false);
    transient private final List<CheckedConsumer<ResourceResolver>> successHandlers = Collections.synchronizedList(new ArrayList<>());
    transient private final List<CheckedBiConsumer<List<Failure>, ResourceResolver>> errorHandlers = Collections.synchronizedList(new ArrayList<>());
    transient private final List<Runnable> finishHandlers = Collections.synchronizedList(new ArrayList<>());

    ActionManagerImpl(String name, ThrottledTaskRunner taskRunner, ResourceResolver resolver, int saveInterval) throws LoginException {
        this.name = name;
        this.taskRunner = taskRunner;
        this.saveInterval = saveInterval;
        baseResolver = resolver.clone(null);
        currentPath = new ThreadLocal<>();
        failures = new ArrayList<>();
    }

    @Override
    public String getName() {
        return name;
    }


    @Override
    public int getAddedCount() {
        return tasksAdded.get();
    }

    @Override
    public int getSuccessCount() {
        return tasksSuccessful.get();
    }

    @Override
    public int getErrorCount() {
        return tasksError.get();
    }

    @Override
    public int getCompletedCount() {
        return tasksCompleted.get();
    }

    @Override
    public int getRemainingCount() {
        return getAddedCount() - (getSuccessCount() + getErrorCount());
    }

    @Override
    public List<Failure> getFailureList() {
        return failures;
    }

    @Override
    public void deferredWithResolver(final Consumer<ResourceResolver> action) {
        this.deferredWithResolver((CheckedConsumer<ResourceResolver>) action);
    }

    @Override
    public void deferredWithResolver(final CheckedConsumer<ResourceResolver> action) {
        deferredWithResolver(action, false);
    }

    @Override
    public void withResolver(Consumer<ResourceResolver> action) throws Exception {
        withResolver((CheckedConsumer<ResourceResolver>) action);
    }

    @Override
    public void withResolver(CheckedConsumer<ResourceResolver> action) throws Exception {
        Actions.setCurrentActionManager(this);
        ReusableResolver resolver = getResourceResolver();
        resolver.setCurrentItem(currentPath.get());
        try {
            action.accept(resolver.getResolver());
        } catch (Throwable ex) {
            throw ex;
        } finally {
            try {
                resolver.free();
            } catch (PersistenceException ex) {
                logPersistenceException(resolver.getPendingItems(), ex);
                throw ex;
            }
            Actions.setCurrentActionManager(null);
        }
    }

    @Override
    public int withQueryResults(
            final String queryStatement,
            final String language,
            final BiConsumer<ResourceResolver, String> callback,
            final BiFunction<ResourceResolver, String, Boolean>... filters
    )
            throws RepositoryException, PersistenceException, Exception {
        return withQueryResults(queryStatement, language, (CheckedBiConsumer<ResourceResolver, String>) callback,
                Arrays.copyOf(filters, filters.length, CheckedBiFunction[].class));
    }
    
    @Override
    public int withQueryResults(
            final String queryStatement,
            final String language,
            final CheckedBiConsumer<ResourceResolver, String> callback,
            final CheckedBiFunction<ResourceResolver, String, Boolean>... filters
    )
            throws RepositoryException, PersistenceException, Exception {
        withResolver((ResourceResolver resolver) -> {
            try {
                Session session = resolver.adaptTo(Session.class);
                QueryManager queryManager = session.getWorkspace().getQueryManager();
                Query query = queryManager.createQuery(queryStatement, language);
                QueryResult results = query.execute();
                for (NodeIterator nodeIterator = results.getNodes(); nodeIterator.hasNext();) {
                    final String nodePath = nodeIterator.nextNode().getPath();
                    LOG.info("Processing found result " + nodePath);
                    deferredWithResolver((ResourceResolver r) -> {
                        currentPath.set(nodePath);
                        if (filters != null) {
                            for (CheckedBiFunction<ResourceResolver, String, Boolean> filter : filters) {
                                if (!filter.apply(r, nodePath)) {
                                    logFilteredOutItem(nodePath);
                                    return;
                                }
                            }
                        }
                        callback.accept(r, nodePath);
                    });
                }
            } catch (RepositoryException ex) {
                LOG.error("Repository exception processing query "+queryStatement, ex);
            }
        });
        
        return tasksAdded.get();
    }
    
    @Override
    public void addCleanupTask() {
        // This is deprecated, only included for backwards-compatibility.
    }
    
    @Override
    public void onSuccess(CheckedConsumer<ResourceResolver> successTask) {
        successHandlers.add(successTask);
    }

    @Override
    public void onFailure(CheckedBiConsumer<List<Failure>, ResourceResolver> failureTask) {
        errorHandlers.add(failureTask);
    }
    
    @Override
    public void onFinish(Runnable finishHandler) {
        finishHandlers.add(finishHandler);
    }
   
    private void runCompletionTasks() {
        if (getErrorCount() == 0) {
            successHandlers.forEach(handler -> {
                try {
                    this.withResolver(handler);
                } catch (Exception ex) {
                    LOG.error("Error in success handler for action "+getName(), ex);
                }
            });
        } else {
            errorHandlers.forEach(handler -> {
                try {
                    this.withResolver(res -> handler.accept(getFailureList(), res));
                } catch (Exception ex) {
                    LOG.error("Error in error handler for action "+getName(), ex);
                }
            });            
        }
        finishHandlers.forEach(Runnable::run);
    }
    
    private void performAutomaticCleanup() {
        if (!cleanupHandlerRegistered.getAndSet(true)) {
            taskRunner.scheduleWork(() -> {
                while (!isComplete()) {
                    try {
                        Thread.sleep(COMPLETION_CHECK_INTERVAL);
                    } catch (InterruptedException ex) {
                        logError(ex);
                    }
                }
                runCompletionTasks();
                closeAllResolvers();
            });        
        }
    }

    @Override
    public void setCurrentItem(String item) {
        currentPath.set(item);
    }

    private void deferredWithResolver(
            final CheckedConsumer<ResourceResolver> action,
            final boolean closesResolver) {
        taskRunner.scheduleWork(() -> {
            started.compareAndSet(0, System.currentTimeMillis());
            try {
                withResolver(action);
                if (!closesResolver) {
                    logCompletetion();
                }
            } catch (Exception ex) {
                LOG.error("Error in error handler for action "+getName(), ex);
                if (!closesResolver) {
                    logError(ex);
                }
            } catch (Throwable t) {
                LOG.error("Fatal uncaught error in error handler for action "+getName(), t);
                if (!closesResolver) {
                    logError(new RuntimeException(t));
                }
                throw t;
            }
        }, this);
        if (!closesResolver) {
            tasksAdded.incrementAndGet();
        }
    }

    private ReusableResolver getResourceResolver() throws LoginException {
        ReusableResolver resolver = currentResolver.get();
        if (resolver == null || !resolver.getResolver().isLive()) {
            resolver = new ReusableResolver(baseResolver.clone(null), saveInterval);
            currentResolver.set(resolver);
            resolvers.add(resolver);
        }
        return resolver;
    }

    private void logCompletetion() {
        tasksCompleted.incrementAndGet();
        tasksSuccessful.incrementAndGet();
        if (isComplete()) {
            finished = System.currentTimeMillis();
            performAutomaticCleanup();
        }
    }

    private void logError(Exception ex) {
        LOG.error("Caught exception in task: "+ex.getMessage(), ex);
        Failure fail = new Failure();
        fail.setNodePath(currentPath.get());
        fail.setException(ex);
        failures.add(fail);
        tasksCompleted.incrementAndGet();
        tasksError.incrementAndGet();
        if (isComplete()) {
            finished = System.currentTimeMillis();
            performAutomaticCleanup();
        }
    }

    private void logPersistenceException(List<String> items, PersistenceException ex) {
        StringBuilder itemList = new StringBuilder();
        for (String item:items) {
            itemList.append(item).append("; ");
            Failure fail = new Failure();
            fail.setNodePath(item);
            fail.setException(ex);
            failures.add(fail);
            tasksError.incrementAndGet();
            tasksSuccessful.decrementAndGet();
        }        
        LOG.error("Persistence error prevented saving changes for: "+itemList, ex);
    }

    private void logFilteredOutItem(String path) {
        tasksFilteredOut.incrementAndGet();
        LOG.info("Filtered out " + path);
    }

    public long getRuntime() {
        if (isComplete()) {
            return finished - started.get();
        } else if (tasksAdded.get() == 0) {
            return 0;
        } else {
            return System.currentTimeMillis() - started.get();
        }
    }

    public static TabularType getStaticsTableType() {
        return statsTabularType;
    }

    @Override
    public boolean isComplete() {
        if (tasksCompleted.get() == tasksAdded.get()) {
            try {
                Thread.sleep(HESITATION_DELAY);
            } catch (InterruptedException ex) {
            }
            return tasksCompleted.get() == tasksAdded.get();
        } else {
            return false;
        }
    }

    @Override
    public CompositeData getStatistics() throws OpenDataException {
        return new CompositeDataSupport(statsCompositeType, statsItemNames, 
                new Object[]{
                    name, 
                    tasksAdded.get(), 
                    tasksCompleted.get(), 
                    tasksFilteredOut.get(), 
                    tasksSuccessful.get(), 
                    tasksError.get(), 
                    getRuntime()
                }
        );
    }

    @Override
    public synchronized void closeAllResolvers() {
        if (!resolvers.isEmpty()) {
            resolvers.stream()
                    .map(ReusableResolver::getResolver)
                    .filter(ResourceResolver::isLive)
                    .forEach(ResourceResolver::close);
            resolvers.clear();
        }
        baseResolver.close();
    }

    static public TabularType getFailuresTableType() {
        return failureTabularType;
    }

    @Override
    public List<CompositeData> getFailures() throws OpenDataException {
        ArrayList<CompositeData> failureData = new ArrayList<>();
        int count = 0;
        for (Failure fail : failures) {
            if (count > 5000) break;
            failureData.add(new CompositeDataSupport(
                    failureCompositeType,
                    failureItemNames,
                    new Object[]{name, ++count, fail.getNodePath(), fail.getException() == null ? "Unknown" : fail.getException().getMessage()}));
        }
        return failureData;
    }

    transient private static String[] statsItemNames;
    transient private static CompositeType statsCompositeType;
    transient private static TabularType statsTabularType;
    transient private static String[] failureItemNames;
    transient private static CompositeType failureCompositeType;
    transient private static TabularType failureTabularType;

    static {
        try {
            statsItemNames = new String[]{"_taskName", "started", "completed", "filtered", "successful", "errors", "runtime"};
            statsCompositeType = new CompositeType(
                    "Statics Row",
                    "Single row of statistics",
                    statsItemNames,
                    new String[]{"Name", "Started", "Completed", "Filtered", "Successful", "Errors", "Runtime"},
                    new OpenType[]{SimpleType.STRING, SimpleType.INTEGER, SimpleType.INTEGER, SimpleType.INTEGER, SimpleType.INTEGER, SimpleType.INTEGER, SimpleType.LONG});
            statsTabularType = new TabularType("Statistics", "Collected statistics", statsCompositeType, new String[]{"_taskName"});

            failureItemNames = new String[]{"_taskName", "_count", "item", "error"};
            failureCompositeType = new CompositeType(
                    "Failure",
                    "Failure",
                    failureItemNames,
                    new String[]{"Name", "#", "Item", "Error"},
                    new OpenType[]{SimpleType.STRING, SimpleType.INTEGER, SimpleType.STRING, SimpleType.STRING});
            failureTabularType = new TabularType("Errors", "Collected failures", failureCompositeType, new String[]{"_taskName", "_count"});
        } catch (OpenDataException ex) {
            LOG.error("Unable to build MBean composite types", ex);
        }
    }
}
