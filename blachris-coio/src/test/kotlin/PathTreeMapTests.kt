import com.github.blachris.coio.PathTreeMap
import com.github.blachris.coio.subTreeValues
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.*

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PathTreeMapTests {

    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @Nested
    inner class `a new map` {
        val map = PathTreeMap<String, String>()

        @Test
        fun `has size 0`() {
            assertThat(map).isEmpty()
            assertThat(map).hasSize(0)
        }

        @Test
        fun `adding element to root is not allowed`() {
            assertThrows<IllegalArgumentException> {
                map.put(emptyList(), "no")
            }
        }

        @Test
        fun `values are valid`() {
            assertThat(map.values).containsOnly()
        }

        @TestInstance(TestInstance.Lifecycle.PER_CLASS)
        @Nested
        inner class `when adding a values` {
            @BeforeAll
            fun setup() {
                map.put(listOf("A", "B"), "test")
            }

            @AfterAll
            fun clean() {
                map.clear()
            }

            @Test
            fun `has size 1`() {
                assertThat(map).isNotEmpty()
                assertThat(map).hasSize(1)
            }

            @Test
            fun `values are valid`() {
                assertThat(map.values).containsOnly("test")
            }

            @TestInstance(TestInstance.Lifecycle.PER_CLASS)
            @Nested
            inner class `when removing same value` {
                @BeforeAll
                fun setup() {
                    map.remove(listOf("A", "B"), "test")
                }

                @Test
                fun `has size 0`() {
                    assertThat(map).hasSize(0)
                }
            }
        }
    }

    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @Nested
    inner class `a map with some values` {
        val map = PathTreeMap<String, String>()

        @BeforeAll
        fun setup() {
            map.put(listOf("Y"), "Y")
            map.put(listOf("A", "A"), "AA")
            map.put(listOf("A", "C"), "AC")
            map.put(listOf("A", "B"), "AB")
            map.put(listOf("Z", "B"), "ZB")
        }

        @Test
        fun `values are valid`() {
            assertThat(map.values).containsOnly("AA", "AB", "AC", "Y", "ZB")
        }

        @TestInstance(TestInstance.Lifecycle.PER_CLASS)
        @Nested
        inner class `when removing elements with iterator` {

            val iter = map.values.iterator()

            @BeforeAll
            fun setup() {
                iter.next()
                iter.next()
                iter.remove()
                iter.next()
                iter.remove()
            }

            @Test
            fun `values are valid`() {
                assertThat(map.values).containsOnly("AA", "Y", "ZB")
            }

            @Test
            fun `second remove fails`() {
                assertThrows<IllegalStateException> { iter.remove() }
            }

            @Test
            fun `has next`() {
                assertThat(iter).hasNext()
            }

            @Test
            fun `size was is valid`() {
                assertThat(map).hasSize(3)
            }

            @Test
            fun `cannot get removed element`() {
                assertThat(map.get(listOf("A", "B"))).isNull()
            }

            @TestInstance(TestInstance.Lifecycle.PER_CLASS)
            @Nested
            inner class `when using another iterator` {
                val iter2 = map.values.iterator()

                @BeforeAll
                fun setup() {
                    while (iter2.hasNext()) {
                        iter2.next()
                        iter2.remove()
                    }
                }

                @Test
                fun `map is empty`() {
                    assertThat(map).hasSize(0)
                    assertThat(map.values).isEmpty()
                    assertThat(map.values).containsOnly()
                }
            }
        }
    }

    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @Nested
    inner class `a map with complex contents` {
        val map = PathTreeMap<String, String>()

        @BeforeAll
        fun setup() {
            map.put(listOf("A"), "A")
            map.put(listOf("B"), "B")
            map.put(listOf("F", "A"), "F/A")
            map.put(listOf("F", "B"), "F/B")
            map.put(listOf("F", "F", "A"), "F/F/A")
        }

        @Test
        fun `has valid size`() {
            assertThat(map).hasSize(5)
        }

        @Test
        fun `contains value`() {
            assertThat(map.containsValue("F/F/A")).isTrue()

            assertThat(map.containsValue("F/F")).isFalse()
        }

        @Test
        fun `subtree iterator works`() {
            var sub = map.getNode(listOf("F"))!!.subTreeValues()
            assertThat(sub.toList()).containsOnly("F/A", "F/B", "F/F/A")

            sub = map.getNode(emptyList())!!.subTreeValues()
            assertThat(sub.toList()).containsOnly("A", "B", "F/A", "F/B", "F/F/A")

            sub = map.getNode(listOf("F", "F"))!!.subTreeValues()
            assertThat(sub.toList()).containsOnly("F/F/A")

            sub = map.getNode(listOf("F", "F", "A"))!!.subTreeValues()
            assertThat(sub.toList()).containsOnly("F/F/A")

            sub = map.getNearestNode(listOf("X")).subTreeValues()
            assertThat(sub.toList()).containsOnly("A", "B", "F/A", "F/B", "F/F/A")

            sub = map.getNearestNode(listOf("F", "F", "A", "B")).subTreeValues()
            assertThat(sub.toList()).containsOnly("F/F/A")
        }

        @Test
        fun `get children works`() {
            assertThat(map.getChildren(emptyList())!!.map { it.key.last() }.toList()).containsOnly("A", "B", "F")

            assertThat(map.getChildren(listOf("X"))).isNull()

            assertThat(map.getChildren(listOf("A"))!!.toList()).isEmpty()

            assertThat(map.getChildren(listOf("F", "F"))!!.map { it.key.last() }.toList()).containsOnly("A")
        }

        @Test
        fun `contains key for entry`() {
            assertThat(map.contains(listOf("F", "F", "A"))).isTrue()

            assertThat(map.contains(listOf("F", "F"))).isFalse()

            assertThat(map.contains(listOf("F"))).isFalse()

            assertThat(map.contains(emptyList())).isFalse()

            assertThat(map.contains(listOf("X"))).isFalse()

            assertThat(map.contains(listOf("F", "F", "A", "X"))).isFalse()
        }

        /*
        @Test
        fun `can add leaves clashing with branches`() {
            assertThrows<IllegalArgumentException> {
                map.put(listOf("F"), "A")
            }

            assertThrows<IllegalArgumentException> {
                map.put(listOf("F", "F"), "A")
            }

            assertThrows<IllegalArgumentException> {
                map.put(listOf("F", "F", "A", "B"), "A")
            }
        }

         */

        @Test
        fun `can overwrite existing values`() {
            val oldSize = map.size

            assertThat(map.put(listOf("A"), "Atest")).isEqualTo("A")

            assertThat(map.put(listOf("F", "F", "A"), "FFAtest")).isEqualTo("F/F/A")

            assertThat(map.put(listOf("F", "F"), "FFtest")).isNull()

            assertThat(map).hasSize(oldSize + 1)

            assertThat(map.remove(listOf("F", "F"))).isNotNull()

            assertThat(map).hasSize(oldSize)
        }

        @TestInstance(TestInstance.Lifecycle.PER_CLASS)
        @Nested
        inner class `when adding and removing some values` {
            @BeforeAll
            fun setup() {
                map.put(listOf("C"), "C")
                map.remove(listOf("B"))
                map.remove(listOf("F", "F", "A"))
                map.put(listOf("F", "F", "A", "A"), "F/F/A/A")
            }

            @Test
            fun `has valid size`() {
                assertThat(map).hasSize(5)
            }

            @Test
            fun `removing value again returns null`() {
                assertThat(map.remove(listOf("B"))).isNull()
            }
        }
    }
}