REMINDER (you just wrote an HTML template): a template file alone is a 404 — it is only served
when a route/controller method renders it. Spring: a `@Controller` (NOT `@RestController`) method
that returns the template's name and fills its `Model` attributes, plus `spring-boot-starter-thymeleaf`.
Express: `res.render(...)` on a route. FastAPI: a route returning `TemplateResponse`. Plain Node:
the HTTP handler must read and send the file. Write the route NOW, and prove the page works with a
test that requests its URL and asserts 200 plus a real content fragment (e.g. MockMvc:
`status().isOk()` and `content().string(containsString("Total"))`).
