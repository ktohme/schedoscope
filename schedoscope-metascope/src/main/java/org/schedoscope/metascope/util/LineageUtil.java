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
package org.schedoscope.metascope.util;

import com.cloudera.com.fasterxml.jackson.core.JsonProcessingException;
import com.cloudera.com.fasterxml.jackson.databind.ObjectMapper;
import org.schedoscope.metascope.model.MetascopeField;
import org.schedoscope.metascope.model.MetascopeTable;
import org.schedoscope.metascope.util.model.Node;

import javax.xml.validation.Schema;
import java.util.*;

public class LineageUtil {

    private static ObjectMapper jsonMapper = new ObjectMapper();

    public static String getDependencyGraph(MetascopeTable table) {
        List<Node> topLevelNodes = getTopLevelNodes(table, 0);
        Collections.sort(topLevelNodes);
        Map<String, Node> visited = new HashMap<>();
        for (Node topLevelNode : topLevelNodes) {
            createDependencyGraph(topLevelNode, visited);
        }

        int i = 0;
        int minLevel = Integer.MAX_VALUE;
        int maxLevel = Integer.MIN_VALUE;
        for (Node node : visited.values()) {
            node.setId(i++);
            if (node.getDistanceToCentralNode() < minLevel) {
                minLevel = node.getDistanceToCentralNode();
            }
            if (node.getDistanceToCentralNode() > maxLevel) {
                maxLevel = node.getDistanceToCentralNode();
            }
        }

        return convertLineageGraphToVisJsNetwork(visited.values(), maxLevel, minLevel);
    }

    private static void createDependencyGraph(Node parent, Map<String, Node> visited) {
        if (visited.get(parent.getTable().getFqdn()) == null) {
            visited.put(parent.getTable().getFqdn(), parent);
            for (MetascopeTable metascopeTable : parent.getTable().getDependencies()) {
                Node n = visited.get(metascopeTable.getFqdn());
                boolean alreadyVisited = true;
                if (n == null) {
                    n = new Node();
                    n.setTable(metascopeTable);
                    n.setDistanceToCentralNode(parent.getDistanceToCentralNode() - 1);
                    alreadyVisited = false;
                }
                n.addToNexts(parent);
                parent.addToPrevious(n);
                if (!alreadyVisited) {
                    createDependencyGraph(n, visited);
                }
            }
        }
    }

    private static List<Node> getTopLevelNodes(MetascopeTable table, int distance) {
        List<Node> toplevelNodes = new ArrayList<>();
        if (table.getSuccessors().isEmpty()) {
            Node node = new Node();
            node.setTable(table);
            node.setDistanceToCentralNode(distance);
            toplevelNodes.add(node);
        } else {
            for (MetascopeTable metascopeTable : table.getSuccessors()) {
                if (metascopeTable.getFqdn().equals(table.getFqdn())) {
                    continue;
                }

                List<Node> nodes = getTopLevelNodes(metascopeTable, distance++);
                if (toplevelNodes.isEmpty()) {
                    toplevelNodes.addAll(nodes);
                } else {
                    List<Node> newNodes = new ArrayList<>();
                    for (Node newNode : nodes) {
                        boolean isNew = true;
                        for (Node existing : toplevelNodes) {
                            if (existing.getTable().equals(newNode.getTable())) {
                                isNew = false;
                                if (existing.getDistanceToCentralNode() < newNode.getDistanceToCentralNode()) {
                                    existing.setDistanceToCentralNode(newNode.getDistanceToCentralNode());
                                }
                            }
                        }
                        if (isNew) {
                            newNodes.add(newNode);
                        }
                    }
                    toplevelNodes.addAll(newNodes);
                }
            }
        }
        return toplevelNodes;
    }

    public static String convertLineageGraphToVisJsNetwork(Collection<Node> graph, int maxLevel, int minLevel) {
        for (Node node : graph) {
            node.setLevel(node.getDistanceToCentralNode() - minLevel);
        }
        String nodes = "{\n \"nodes\": [\n";
        String edges = "\n \"edges\": [\n";
        int nodeCounter = 0;
        int edgeCounter = 0;
        for (Node n : graph) {
            if (nodeCounter > 0) {
                nodes += ", ";
            }
            String tableName = n.getTable().getFqdn().replace(".", "\\n");
            nodes += "{\n \"id\": " + n.getId() + ",\n \"label\": \"" + tableName + "\",\n \"group\": \"tables\""
                    + ",\n \"level\": " + n.getLevel() * 2 + ",\n \"fqdn\": \"" + n.getTable().getFqdn() + "\"\n}";
            nodeCounter++;
            for (Node d : n.getNexts()) {
                if (edgeCounter > 0) {
                    edges += ", ";
                }
                edges += "{\n \"from\": " + d.getId() + ",\n \"to\": " + n.getId() + ",\n \"arrows\": \"from\"\n}";
                edgeCounter++;
            }
        }
        nodes += "],";
        edges += "]}";
        return nodes + edges;
    }

    public static String getSchemaLineageJson(MetascopeTable table) {
        SchemaLineage schemaLineage = new SchemaLineage();

        List<SchemaLineageEdge> forwardEdges = new ArrayList<>();
        List<SchemaLineageEdge> backwardEdges = new ArrayList<>();

        for (MetascopeField metascopeField : table.getFields()) {
            forwardSchemaLineage(metascopeField, forwardEdges);
        }

        for (SchemaLineageEdge forwardEdge : forwardEdges) {
            System.out.println(forwardEdge.from.getId() + " -> " + forwardEdge.to.getId());
        }

        for (MetascopeField metascopeField : table.getFields()) {
            backwardSchemaLineage(metascopeField, backwardEdges);
        }

        System.out.println("____");
        for (SchemaLineageEdge backwardEdge : backwardEdges) {
            System.out.println(backwardEdge.from.getId() + " -> " + backwardEdge.to.getId());
        }

        schemaLineage.setForwardEdges(forwardEdges);
        schemaLineage.setBackwardEdges(backwardEdges);

        try {
            return jsonMapper.writeValueAsString(schemaLineage);
        } catch (JsonProcessingException e) {
            return "505 Internal Server Error";
        }
    }

    private static void forwardSchemaLineage(MetascopeField mField, List<SchemaLineageEdge> nodes) {
        for (MetascopeField field : mField.getSuccessors()) {
            SchemaLineageNode from = new SchemaLineageNode(mField.getFieldId(), mField.getFieldName(), mField.getTable().getFqdn());
            SchemaLineageNode to = new SchemaLineageNode(field.getFieldId(), field.getFieldName(), field.getTable().getFqdn());
            SchemaLineageEdge edge = new SchemaLineageEdge(from, to);
            nodes.add(edge);
            forwardSchemaLineage(field, nodes);
        }
    }

    private static void backwardSchemaLineage(MetascopeField mField, List<SchemaLineageEdge> nodes) {
        for (MetascopeField field : mField.getDependencies()) {
            SchemaLineageNode from = new SchemaLineageNode(mField.getFieldId(), mField.getFieldName(), mField.getTable().getFqdn());
            SchemaLineageNode to = new SchemaLineageNode(field.getFieldId(), field.getFieldName(), field.getTable().getFqdn());
            SchemaLineageEdge edge = new SchemaLineageEdge(from, to);
            nodes.add(edge);
            backwardSchemaLineage(field, nodes);
        }
    }

    private static class SchemaLineage {

        private List forwardEdges;
        private List backwardEdges;

        public void setForwardEdges(List forwardEdges) {
            this.forwardEdges = forwardEdges;
        }

        public void setBackwardEdges(List backwardEdges) {
            this.backwardEdges = backwardEdges;
        }

        public List getForwardEdges() {
            return forwardEdges;
        }

        public List getBackwardEdges() {
            return backwardEdges;
        }

    }

    private static class SchemaLineageEdge {

        private SchemaLineageNode from;
        private SchemaLineageNode to;

        public SchemaLineageEdge(SchemaLineageNode from, SchemaLineageNode to) {
            this.from = from;
            this.to = to;
        }

        public void setFrom(SchemaLineageNode from) {
            this.from = from;
        }

        public void setTo(SchemaLineageNode to) {
            this.to = to;
        }

        public SchemaLineageNode getFrom() {
            return from;
        }

        public SchemaLineageNode getTo() {
            return to;
        }

    }

    private static class SchemaLineageNode {

        private String id;
        private String label;
        private String parent;

        public SchemaLineageNode(String id, String label, String parent) {
            this.id = id;
            this.label = label;
            this.parent = parent;
        }

        public void setId(String id) {
            this.id = id;
        }

        public void setLabel(String label) {
            this.label = label;
        }

        public void setParent(String parent) {
            this.parent = parent;
        }

        public String getId() {
            return id;
        }

        public String getLabel() {
            return label;
        }

        public String getParent() {
            return parent;
        }

    }

}
