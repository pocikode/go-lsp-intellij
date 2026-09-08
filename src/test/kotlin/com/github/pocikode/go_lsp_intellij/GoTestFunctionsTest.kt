package com.github.pocikode.go_lsp_intellij

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GoTestFunctionsTest {

    private fun names(source: String): List<String> = GoTestFunctions.find(source).map { it.name }

    @Test
    fun `finds every kind of test function`() {
        val source = """
            package pkg

            func TestOne(t *testing.T) {}
            func BenchmarkTwo(b *testing.B) {}
            func FuzzThree(f *testing.F) {}
            func ExampleFour() {}
        """.trimIndent()
        assertEquals(listOf("TestOne", "BenchmarkTwo", "FuzzThree", "ExampleFour"), names(source))
    }

    @Test
    fun `requires the character after the prefix not to be lowercase`() {
        val source = """
            package pkg

            func Testing(t *testing.T) {}
            func Benchmarking(b *testing.B) {}
            func Test(t *testing.T) {}
            func Test_underscore(t *testing.T) {}
        """.trimIndent()
        assertEquals(listOf("Test", "Test_underscore"), names(source))
    }

    @Test
    fun `leaves TestMain out, as -run cannot select it`() {
        assertEquals(emptyList<String>(), names("package pkg\n\nfunc TestMain(m *testing.M) {}\n"))
    }

    @Test
    fun `ignores methods, which a receiver keeps from being a test`() {
        assertEquals(emptyList<String>(), names("package pkg\n\nfunc (s *Suite) TestFoo() {}\n"))
    }

    @Test
    fun `ignores a declaration that is not at the top level`() {
        assertEquals(emptyList<String>(), names("package pkg\n\nvar x = func TestNo() {}\n"))
    }

    @Test
    fun `points at the identifier, not the func keyword`() {
        val source = "package pkg\n\nfunc TestOne(t *testing.T) {}\n"
        val declaration = GoTestFunctions.find(source).single()
        assertEquals("TestOne", source.substring(declaration.nameOffset, declaration.nameOffset + "TestOne".length))
    }

    @Test
    fun `finds direct literal subtests`() {
        val source = """
            package pkg

            func TestOne(t *testing.T) {
                t.Run("first case", func(t *testing.T) {})
                t.Run(`second/case`, func(t *testing.T) {})
            }
        """.trimIndent()
        val subtests = GoTestFunctions.find(source).single().subtests
        assertEquals(listOf("first_case", "second/case"), subtests.map { it.name })
        assertEquals("first case", source.substring(subtests[0].nameOffset, subtests[0].nameOffset + 10))
    }

    @Test
    fun `normalizes subtest names the way Go testing does`() {
        val source = """
            package pkg

            func TestOne(t *testing.T) {
                t.Run("line\nbell\x07", func(t *testing.T) {})
            }
        """.trimIndent()
        assertEquals(listOf("line_bell\\a"), GoTestFunctions.find(source).single().subtests.map { it.name })
    }

    @Test
    fun `finds names in a conventional table driven test`() {
        val source = """
            package pkg

            func TestOne(t *testing.T) {
                tests := []struct {
                    name string
                    want int
                }{
                    {name: "empty", want: 0},
                    {name: "with value", want: 1},
                }
                for _, tt := range tests {
                    t.Run(tt.name, func(t *testing.T) {})
                }
            }
        """.trimIndent()
        assertEquals(
            listOf("empty", "with_value"),
            GoTestFunctions.find(source).single().subtests.map { it.name },
        )
    }

    @Test
    fun `uses the selected table field rather than every string field`() {
        val source = """
            package pkg

            func TestOne(t *testing.T) {
                cases := []struct { label, input string }{
                    {label: "case one", input: "ignored"},
                }
                for _, tc := range cases {
                    t.Run(tc.label, func(t *testing.T) {})
                }
            }
        """.trimIndent()
        assertEquals(listOf("case_one"), GoTestFunctions.find(source).single().subtests.map { it.name })
    }

    @Test
    fun `finds names in positional anonymous struct table rows`() {
        val source = """
            package pkg

            func TestOne(t *testing.T) {
                cases := []struct { input string; label string }{
                    {"ignored", "first case"},
                    {"ignored too", "second"},
                }
                for _, tc := range cases {
                    t.Run(tc.label, func(t *testing.T) {})
                }
            }
        """.trimIndent()
        assertEquals(
            listOf("first_case", "second"),
            GoTestFunctions.find(source).single().subtests.map { it.name },
        )
    }

    @Test
    fun `ignores dynamic table names it cannot select statically`() {
        val source = """
            package pkg

            func TestOne(t *testing.T) {
                for _, tt := range makeCases() {
                    t.Run(tt.name, func(t *testing.T) {})
                }
            }
        """.trimIndent()
        assertEquals(emptyList<GoTestFunctions.Subtest>(), GoTestFunctions.find(source).single().subtests)
    }

    @Test
    fun `anchors a single test name`() {
        assertEquals("^TestOne$", GoTestFunctions.runPattern(listOf("TestOne")))
    }

    @Test
    fun `anchors every part of a subtest path separately`() {
        assertEquals("^TestOne$/^first_case$", GoTestFunctions.runPattern(listOf("TestOne/first_case")))
    }

    @Test
    fun `escapes what RE2 would otherwise read as a pattern`() {
        assertEquals("""^TestOne$/^a\+b\(c\)$""", GoTestFunctions.runPattern(listOf("TestOne/a+b(c)")))
    }

    @Test
    fun `joins several names into one alternation`() {
        assertEquals("^(TestOne|TestTwo)$", GoTestFunctions.runPattern(listOf("TestOne", "TestTwo")))
    }

    @Test
    fun `reduces several names to their top-level tests, which is all one pattern can express`() {
        assertEquals(
            "^(TestOne|TestTwo)$",
            GoTestFunctions.runPattern(listOf("TestOne/a", "TestOne/b", "TestTwo/c")),
        )
    }

    @Test
    fun `selects everything when there is nothing to select`() {
        assertEquals("", GoTestFunctions.runPattern(emptyList()))
    }
}
