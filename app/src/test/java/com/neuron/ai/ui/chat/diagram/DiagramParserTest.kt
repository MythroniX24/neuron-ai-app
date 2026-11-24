package com.neuron.ai.ui.chat.diagram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagramParserTest {

    @Test
    fun testParseTriangleGeometry() {
        val json = """
            {
                "type": "triangle",
                "title": "Pythagorean Triangle",
                "points": [
                    {"id": "A", "label": "A", "x": 0.0, "y": 0.0},
                    {"id": "B", "label": "B", "x": 0.0, "y": 40.0},
                    {"id": "C", "label": "C", "x": 30.0, "y": 40.0}
                ],
                "lines": [
                    {"from": "A", "to": "B", "label": "c", "length": "4"},
                    {"from": "B", "to": "C", "label": "a", "length": "3"},
                    {"from": "C", "to": "A", "label": "b", "length": "5"}
                ],
                "angles": [
                    {"vertex": "B", "p1": "A", "p2": "C", "isRightAngle": true}
                ]
            }
        """.trimIndent()

        val spec = DiagramParser.parse("geometry", json)
        assertNotNull(spec)
        assertEquals("triangle", spec!!.type)
        assertEquals("Pythagorean Triangle", spec.title)
        assertEquals(3, spec.points.size)
        assertEquals(3, spec.lines.size)
        assertEquals(1, spec.angles.size)

        // Angle at B between A and C should be 90 degrees
        val pA = spec.points.first { it.id == "A" }
        val pB = spec.points.first { it.id == "B" }
        val pC = spec.points.first { it.id == "C" }
        val angleB = DiagramParser.calculateAngle(pB, pA, pC)
        assertEquals(90f, angleB, 0.1f)

        // Distances
        assertEquals(40f, DiagramParser.calculateDistance(pA, pB), 0.1f)
        assertEquals(30f, DiagramParser.calculateDistance(pB, pC), 0.1f)
        assertEquals(50f, DiagramParser.calculateDistance(pA, pC), 0.1f)
    }

    @Test
    fun testParseTreeDiagram() {
        val json = """
            {
                "type": "tree",
                "title": "Organization Chart",
                "root": {
                    "id": "ceo",
                    "label": "CEO",
                    "children": [
                        {"id": "cto", "label": "CTO"},
                        {"id": "cfo", "label": "CFO"}
                    ]
                }
            }
        """.trimIndent()

        val spec = DiagramParser.parse("tree", json)
        assertNotNull(spec)
        assertEquals("tree", spec!!.type)
        assertEquals("Organization Chart", spec.title)
        assertNotNull(spec.treeRoot)
        assertEquals("CEO", spec.treeRoot!!.label)
        assertEquals(2, spec.treeRoot!!.children.size)
    }
}
