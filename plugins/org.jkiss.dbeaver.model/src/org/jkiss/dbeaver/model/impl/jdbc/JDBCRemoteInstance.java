/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2019 Serge Rider (serge@jkiss.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jkiss.dbeaver.model.impl.jdbc;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.ModelPreferences;
import org.jkiss.dbeaver.model.exec.DBCException;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSInstance;
import org.jkiss.dbeaver.model.struct.DBSObject;

import java.util.ArrayList;
import java.util.List;

/**
 * JDBC data source
 */
public class JDBCRemoteInstance<DATASOURCE extends JDBCDataSource> implements DBSInstance {
    private static final Log log = Log.getLog(JDBCRemoteInstance.class);

    @NotNull
    protected final DATASOURCE dataSource;
    @Nullable
    protected JDBCExecutionContext executionContext;
    @Nullable
    protected JDBCExecutionContext metaContext;
    @NotNull
    private final List<JDBCExecutionContext> allContexts = new ArrayList<>();

    protected JDBCRemoteInstance(@NotNull DBRProgressMonitor monitor, @NotNull DATASOURCE dataSource, boolean initContext)
        throws DBException {
        this.dataSource = dataSource;
        if (initContext) {
            initializeMainContext(monitor);
        }
    }

    @Override
    public DBSObject getParentObject() {
        return dataSource;
    }

    @NotNull
    @Override
    public DATASOURCE getDataSource() {
        return dataSource;
    }

    @NotNull
    @Override
    public String getName() {
        return dataSource.getName();
    }

    @Override
    public boolean isPersisted() {
        return true;
    }

    @Override
    public String getDescription() {
        return null;
    }

    protected void initializeMainContext(@NotNull DBRProgressMonitor monitor) throws DBCException {
        if (executionContext == null) {
            this.executionContext = dataSource.createExecutionContext(this, JDBCExecutionContext.TYPE_MAIN);
            this.executionContext.connect(monitor, null, null, false, true);
        }
    }

    public JDBCExecutionContext initializeMetaContext(@NotNull DBRProgressMonitor monitor)
        throws DBException {
        if (this.metaContext != null) {
            return this.metaContext;
        }
        if (!dataSource.getContainer().getDriver().isEmbedded() && dataSource.getContainer().getPreferenceStore().getBoolean(ModelPreferences.META_SEPARATE_CONNECTION)) {
            synchronized (allContexts) {
                this.metaContext = dataSource.createExecutionContext(this, JDBCExecutionContext.TYPE_METADATA);
                this.metaContext.connect(monitor, true, null, false, true);
                return this.metaContext;
            }
        } else {
            return this.executionContext;
        }
    }

    @NotNull
    @Override
    public DBCExecutionContext openIsolatedContext(@NotNull DBRProgressMonitor monitor, @NotNull String purpose) throws DBException {
        JDBCExecutionContext context = dataSource.createExecutionContext(this, purpose);
        context.connect(monitor, null, null, true, true);
        return context;
    }

    @NotNull
    @Override
    public JDBCExecutionContext[] getAllContexts() {
        synchronized (allContexts) {
            return allContexts.toArray(new JDBCExecutionContext[0]);
        }
    }

    @NotNull
    @Override
    public JDBCExecutionContext getDefaultContext(DBRProgressMonitor monitor, boolean meta) {
        if (metaContext != null && (meta || executionContext == null)) {
            return this.metaContext;
        }
        if (executionContext == null) {
            log.debug("No execution context within database instance");
            return null;
        }
        return executionContext;
    }

    @Override
    public void shutdown(DBRProgressMonitor monitor) {
        shutdown(monitor, false);
    }

    /**
     * Closes all instance contexts
     *
     * @param monitor  progress monitor
     * @param keepMeta do not close meta context
     */
    public void shutdown(DBRProgressMonitor monitor, boolean keepMeta) {
        // [JDBC] Need sync here because real connection close could take some time
        // while UI may invoke callbacks to operate with connection
        List<JDBCExecutionContext> ctxCopy;
        synchronized (allContexts) {
            ctxCopy = new ArrayList<>(allContexts);
        }
        for (JDBCExecutionContext context : ctxCopy) {
            if (keepMeta && context == metaContext) {
                continue;
            }
            monitor.subTask("Close context '" + context.getContextName() + "'");
            context.close();
            monitor.worked(1);
        }
    }

    void addContext(JDBCExecutionContext context) {
        synchronized (allContexts) {
            allContexts.add(context);
        }
    }

    boolean removeContext(JDBCExecutionContext context) {
        synchronized (allContexts) {
            if (context == executionContext) {
                executionContext = null;
            }
            if (context == metaContext) {
                metaContext = null;
            }
            return allContexts.remove(context);
        }
    }
}
