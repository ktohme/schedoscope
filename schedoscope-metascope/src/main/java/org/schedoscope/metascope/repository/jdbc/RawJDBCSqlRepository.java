/**
 * Copyright 2017 Otto (GmbH & Co KG)
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.schedoscope.metascope.repository.jdbc;

import org.apache.commons.dbutils.DbUtils;
import org.schedoscope.metascope.model.MetascopeView;
import org.schedoscope.metascope.task.model.FieldDependency;
import org.schedoscope.metascope.task.model.ViewDependency;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

public class RawJDBCSqlRepository {

    private static final Logger LOG = LoggerFactory.getLogger(RawJDBCSqlRepository.class);
    private final boolean isMySQLDatabase;
    private final boolean isH2Database;

    public RawJDBCSqlRepository(boolean isMySQLDatabase, boolean isH2Database) {
        this.isMySQLDatabase = isMySQLDatabase;
        this.isH2Database = isH2Database;
    }

    public void insertOrUpdateViews(Connection connection, Iterable<MetascopeView> views) {
        String deleteQuery = "delete from metascope_view";
        String insertViewSql = "insert into metascope_view (view_id, view_url, parameter_string, table_fqdn) values "
                + "(?, ?, ?, ?) on duplicate key update view_id=values(view_id), view_url=values(view_url), "
                + "parameter_string=values(parameter_string), table_fqdn=values(table_fqdn)";
        PreparedStatement stmt = null;
        try {
            int batch = 0;
            disableChecks(connection);

            Statement deleteStmt = connection.createStatement();
            deleteStmt.execute(deleteQuery);
            deleteStmt.close();

            stmt = connection.prepareStatement(insertViewSql);
            for (MetascopeView viewEntity : views) {
                stmt.setString(1, viewEntity.getViewId());
                stmt.setString(2, viewEntity.getViewUrl());
                stmt.setString(3, viewEntity.getParameterString());
                stmt.setString(4, viewEntity.getTable().getFqdn());
                stmt.addBatch();
                batch++;
                if (batch % 1024 == 0) {
                    stmt.executeBatch();
                }
            }
            stmt.executeBatch();
            connection.commit();
            enableChecks(connection);
        } catch (SQLException e) {
            LOG.error("Could not save view", e);
        } finally {
            DbUtils.closeQuietly(stmt);
        }
    }

    public void insertFieldDependencies(Connection connection, List<FieldDependency> fieldDependencies) {
        String deleteQuery = "delete from metascope_field_relationship";
        String sql = "insert into metascope_field_relationship (successor, dependency) values (?, ?) "
                + "on duplicate key update successor=values(successor), dependency=values(dependency)";
        PreparedStatement stmt = null;
        try {
            int batch = 0;
            disableChecks(connection);

            Statement deleteStmt = connection.createStatement();
            deleteStmt.execute(deleteQuery);
            deleteStmt.close();

            stmt = connection.prepareStatement(sql);
            for (FieldDependency fieldDependency : fieldDependencies) {
                stmt.setString(1, fieldDependency.getDependency());
                stmt.setString(2, fieldDependency.getSuccessor());
                stmt.addBatch();
                batch++;
                if (batch % 1024 == 0) {
                    stmt.executeBatch();
                }
            }
            stmt.executeBatch();
            connection.commit();
            enableChecks(connection);
        } catch (SQLException e) {
            LOG.error("Could not save view", e);
        } finally {
            DbUtils.closeQuietly(stmt);
        }
    }

    public void insertViewDependencies(Connection connection, List<ViewDependency> viewDependencies) {
        String deleteQuery = "delete from metascope_view_relationship";
        String sql = "insert into metascope_view_relationship (successor, dependency) values (?, ?) "
                + "on duplicate key update successor=values(successor), dependency=values(dependency)";
        PreparedStatement stmt = null;
        try {
            int batch = 0;
            disableChecks(connection);

            Statement deleteStmt = connection.createStatement();
            deleteStmt.execute(deleteQuery);
            deleteStmt.close();

            stmt = connection.prepareStatement(sql);
            for (ViewDependency viewDependency : viewDependencies) {
                stmt.setString(1, viewDependency.getDependency());
                stmt.setString(2, viewDependency.getSuccessor());
                stmt.addBatch();
                batch++;
                if (batch % 1024 == 0) {
                    stmt.executeBatch();
                }
            }
            stmt.executeBatch();
            connection.commit();
            enableChecks(connection);
        } catch (SQLException e) {
            LOG.error("Could not save view", e);
        } finally {
            DbUtils.closeQuietly(stmt);
        }
    }

    public void updateStatus(Connection connection, Iterable<MetascopeView> views) {
        String updateStatus = "update metascope_view set last_transformation=?, total_size=?, num_rows=? where view_id = ?";
        PreparedStatement updateStatusStmt = null;
        try {
            int batch = 0;
            disableChecks(connection);
            updateStatusStmt = connection.prepareStatement(updateStatus);
            for (MetascopeView viewEntity : views) {
                updateStatusStmt.setLong(1, viewEntity.getLastTransformation());
                updateStatusStmt.setLong(2, viewEntity.getTotalSize());
                updateStatusStmt.setLong(3, viewEntity.getNumRows());
                updateStatusStmt.setString(4, viewEntity.getViewId());
                updateStatusStmt.addBatch();
                batch++;
                if (batch % 1024 == 0) {
                    updateStatusStmt.executeBatch();
                }
            }
            updateStatusStmt.executeBatch();
            connection.commit();
            enableChecks(connection);
        } catch (SQLException e) {
            LOG.error("Could not update view", e);
        } finally {
            DbUtils.closeQuietly(updateStatusStmt);
        }
    }

    private void disableChecks(Connection connection) {
        try {
            Statement stmt = connection.createStatement();
            connection.setAutoCommit(false);
            if (isMySQLDatabase) {
                stmt.addBatch("set foreign_key_checks=0");
                stmt.addBatch("set unique_checks=0");
            }
            if (isH2Database) {
                stmt.addBatch("SET REFERENTIAL_INTEGRITY FALSE");
            }
            stmt.executeBatch();
            stmt.close();
        } catch (SQLException e) {
            LOG.error("Could not disable checks", e);
        }
    }

    private void enableChecks(Connection connection) {
        try {
            Statement stmt = connection.createStatement();
            connection.setAutoCommit(true);
            if (isMySQLDatabase) {
                stmt.addBatch("set foreign_key_checks=1");
                stmt.addBatch("set unique_checks=1");
            }
            if (isH2Database) {
                stmt.addBatch("SET REFERENTIAL_INTEGRITY TRUE");
            }
            stmt.executeBatch();
            stmt.close();
        } catch (SQLException e) {
            LOG.error("Could not enable checks", e);
        }
    }

}