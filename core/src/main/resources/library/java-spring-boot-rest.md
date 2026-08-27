# Java · Spring Boot · REST CRUD — worked example (adapt names; do NOT copy verbatim)

Layering: controller → service → repository (extends JpaRepository) → entity (@Entity); request/response DTOs as records. Constructor injection only (never @Autowired fields). Imports are `jakarta.*`, never `javax.*`.

The idioms most often gotten wrong — get these exactly right:

- A POST handler MUST annotate the body parameter with `@RequestBody`, or the JSON body
  is NOT bound and its fields arrive null:

      @PostMapping
      public ResponseEntity<Thing> create(@RequestBody CreateThingRequest req) {
          Thing saved = service.create(req.name(), req.kind());
          return ResponseEntity.status(HttpStatus.CREATED).body(saved);   // 201
      }

- GET-by-id returns 404 when absent — return it, do NOT throw:

      @GetMapping("/{id}")
      public ResponseEntity<Thing> get(@PathVariable Long id) {
          return service.find(id)
                  .map(ResponseEntity::ok)
                  .orElse(ResponseEntity.notFound().build());             // 200 or 404
      }

- DELETE returns 204:

      @DeleteMapping("/{id}")
      public ResponseEntity<Void> delete(@PathVariable Long id) {
          service.delete(id);
          return ResponseEntity.noContent().build();                     // 204
      }

- GET list returns 200 with a JSON array:  `return ResponseEntity.ok(service.list());`

- Entity: `@Entity`, `@Id @GeneratedValue(strategy = GenerationType.IDENTITY)`, a no-arg
  constructor, and a getter per field. Jackson serializes via getters — a field with no
  getter is silently ABSENT from the JSON response.

- Tests: build.gradle needs BOTH the dependency AND the platform line — without
  `useJUnitPlatform()`, JUnit-5 tests compile but are SILENTLY SKIPPED (gradle exits
  green having run nothing):

      dependencies { testImplementation 'org.springframework.boot:spring-boot-starter-test' }
      tasks.named('test') { useJUnitPlatform() }

  Verify endpoints with an in-process integration test — real assertions on status + body:

      @SpringBootTest
      @AutoConfigureMockMvc
      class ApiIntegrationTest {
          @Autowired MockMvc mockMvc;
          @Test void createReturns201() throws Exception {
              mockMvc.perform(post("/api/things").contentType(MediaType.APPLICATION_JSON)
                      .content("{\"name\":\"x\"}"))
                  .andExpect(status().isCreated())
                  .andExpect(jsonPath("$.name").value("x"));
          }
      }

  After `gradle test`, confirm tests RAN — `ls build/test-results/test/` must show XML; a
  green build that executed zero tests proves nothing.

- Fast iteration: full `@SpringBootTest` boots the whole context every run (slow). While
  iterating, prefer a SLICE — `@WebMvcTest(ThingController.class)` boots only the web layer
  (mock the service with `@MockBean`); `@DataJpaTest` boots only JPA + H2. Run one class at
  a time with `gradle test --tests ThingControllerTest`. Also create `gradle.properties`:

      org.gradle.caching=true
      org.gradle.parallel=true

- A server-rendered page (Thymeleaf/JSP) renders ONLY through a `@Controller` method that
  returns the template's name and supplies its model attributes — a template file with no
  controller behind it is a 404, even though the HTML sits in `src/main/resources/templates/`:

      @Controller
      public class DashboardController {
          private final StatsService stats;
          DashboardController(StatsService stats) { this.stats = stats; }
          @GetMapping("/dashboard")
          public String dashboard(Model model) {
              model.addAttribute("stats", stats.summary());
              return "dashboard";              // renders templates/dashboard.html
          }
      }

  (needs `spring-boot-starter-thymeleaf`; `@Controller`, NOT `@RestController` — that would
  return the string "dashboard" as the body). For EVERY page or endpoint the goal names,
  write a MockMvc test proving it returns 200 with real content:

      mockMvc.perform(get("/dashboard"))
          .andExpect(status().isOk())
          .andExpect(content().string(containsString("Total")));

- `@SpringBootTest` boots the WHOLE ApplicationContext — if the context can't load, every
  integration test fails with "Failed to load ApplicationContext". Keep the context bootable:

      dependencies { runtimeOnly 'com.h2database:h2' }     // JPA + H2 boots with ZERO datasource config

  Every `@Value("${some.prop}")` a bean requires must have a default (`@Value("${some.prop:fallback}")`)
  or an entry in `src/main/resources/application.properties` — one missing property kills the
  whole context, and with it every test. If the app reads a file path from config, default it
  to the bundled fixture so tests run offline.
