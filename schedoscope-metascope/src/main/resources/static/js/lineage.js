"use strict";

/**
 * Represents a graph to show the lineage of a view's fields.
 * @param {string} containerSelector A W3C selector string for the container to draw into
 * @param {string} direction Direction for rank nodes. Can be TB, BT, LR or RL, where T = top, B = bottom, L = left and R = right.
 * @constructor
 */
function LineageGraph(containerSelector, direction) {
    const CLUSTER_LABEL_POS = "top";
    const SVG_NAMESPACE_URI = "http://www.w3.org/2000/svg";

    var container = document.querySelector(containerSelector);
    if (container === null) throw "The selector '" + containerSelector + "' does not match any element.";

    var svg = document.createElementNS(SVG_NAMESPACE_URI, "svg");
    container.appendChild(svg);
    var d3svg = d3.select(svg);
    var render = new dagreD3.render();
    var parents = {};
    var fullGraph = new dagreD3.graphlib.Graph()
        .setGraph({})
        .setDefaultEdgeLabel(function () {
            return {};
        })
        .setDefaultNodeLabel(function () {
            return {};
        });
    var drawnGraph = new dagreD3.graphlib.Graph({compound: true, label: "Überschrift"})
        .setGraph({
            rankdir: direction,
            transition: function (selection) {
                var transition = selection.transition();
                transition.each("start", adaptSvgDimension);
                transition.each("end", adaptSvgDimension);
                return transition.duration(512);
            }
        })
        .setDefaultEdgeLabel(function () {
            return {};
        })
        .setDefaultNodeLabel(function (nodeId) {
            return fullGraph.node(nodeId);
        });

    var adaptSvgDimension = function () {
        var boundingBox = svg.getBBox();
        if (!svg.getAttribute("width") || boundingBox.width > parseInt(svg.getAttribute("width"))) {
            svg.setAttribute("width", boundingBox.width.toString());
        }
        if (!svg.getAttribute("height") || boundingBox.height > parseInt(svg.getAttribute("height"))) {
            svg.setAttribute("height", boundingBox.height.toString());
        }
    };

    var redraw = function () {
        render(d3svg, drawnGraph);
        d3svg.selectAll(".node.inner").on("click", addAllSuccessors);
    };

    var addSourceNode = function (id) {
        // add the node
        drawnGraph.setNode(id);

        // add the parent node
        var parent = parents[id];
        drawnGraph.setNode(parent, {label: parent, clusterLabelPos: CLUSTER_LABEL_POS});
        drawnGraph.setParent(id, parent);

        redraw();
    };

    var addEdge = function (edge) {
        // add the edge
        drawnGraph.setEdge(edge.v, edge.w);

        // add the parent (cluster)
        var parent = parents[edge.w];
        drawnGraph.setNode(parent, {label: parent, clusterLabelPos: CLUSTER_LABEL_POS});
        drawnGraph.setParent(edge.w, parent);

        // add the "leaf" class if needed
        if (fullGraph.successors(edge.w).length === 0) {
            drawnGraph.node(edge.w).class = "leaf";
        }
    };

    var getSubtreeEdges = function (nodeId) {
        var outEdges = fullGraph.outEdges(nodeId);
        return outEdges.map(function (edge) {
            return getSubtreeEdges(edge.w)
        }).reduce(function (acc, val) {
            return acc.concat(val)
        }, outEdges)
    };

    var addAllSuccessors = function (nodeId) {
        getSubtreeEdges(nodeId).forEach(addEdge);
        redraw();
    };

    /**
     * Sets the data to display in the graph. All sources will be display initially.
     *
     * @param {Object[]} edgesData All edges to display.
     */
    this.setData = function (edgesData) {
        edgesData.forEach(function (edgeData) {
            fullGraph.setEdge(edgeData.from.id, edgeData.to.id);
            [edgeData.from, edgeData.to].forEach(function (node) {
                fullGraph.setNode(node.id, {label: node.label, class: "inner"});
                parents[node.id] = node.parent;
            });
        });

        fullGraph.sources().forEach(addSourceNode)
    };
}

document.addEventListener("DOMContentLoaded", function () {
    var backwardGraph = new LineageGraph("#backward-lineage-container", "LR");
    var forwardGraph = new LineageGraph("#forward-lineage-container", "RL");

    var xhr = new XMLHttpRequest();
    xhr.onreadystatechange = function () {
        if (xhr.readyState !== XMLHttpRequest.DONE) return;
        if (xhr.status === 200) {
            var dataJson = JSON.parse(xhr.responseText);
            backwardGraph.setData(dataJson["backwardEdges"]);
            forwardGraph.setData(dataJson["forwardEdges"]);
        } else {
            alert("Could not load lineage from backend: " + xhr.statusText);
        }
    };
    xhr.open("GET", "/table/schema/lineage?fqdn=demo_schedoscope_example_osm_processed.nodes_with_geohash");
    xhr.setRequestHeader("Content-Type", "application/json");
    xhr.send();
});