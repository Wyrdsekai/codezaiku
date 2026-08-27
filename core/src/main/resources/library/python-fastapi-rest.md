# Python · FastAPI · REST — worked example (adapt names; do NOT copy verbatim)

Layering: router (app/routes/) → uses a service or the DB session → SQLAlchemy model (app/models/)
→ Pydantic schema (app/schemas/). If a skeleton already provides `Base`, `engine`, `SessionLocal`,
`get_db`, and `settings` (app/db/session.py, app/config.py) — **import and use them; do NOT rewrite
or rename them.** Other files depend on those exact names.

- SQLAlchemy 2.0 model — use `Mapped[...]` + `mapped_column(...)`, NOT the old `Column(...)` class
  attribute style:

      from sqlalchemy.orm import Mapped, mapped_column
      from app.db.base import Base

      class Thing(Base):
          __tablename__ = "things"
          id: Mapped[int] = mapped_column(primary_key=True)
          name: Mapped[str] = mapped_column(nullable=False)

- Pydantic v2 schema — use `model_config = ConfigDict(from_attributes=True)` to read ORM objects,
  and `.model_dump()` (NOT `.dict()`):

      from pydantic import BaseModel, ConfigDict

      class ThingCreate(BaseModel):
          name: str

      class ThingOut(BaseModel):
          model_config = ConfigDict(from_attributes=True)
          id: int
          name: str

- FastAPI router — `APIRouter`, the `Annotated[Session, Depends(get_db)]` DI pattern, explicit
  status codes; register it in app/main.py with `app.include_router(router, prefix="/api")`:

      from typing import Annotated
      from fastapi import APIRouter, Depends, HTTPException
      from sqlalchemy.orm import Session
      from app.db.session import get_db
      from app.models.thing import Thing
      from app.schemas.thing import ThingCreate, ThingOut

      router = APIRouter(prefix="/things", tags=["things"])
      Db = Annotated[Session, Depends(get_db)]

      @router.get("", response_model=list[ThingOut])
      def list_things(db: Db):
          return db.query(Thing).all()

      @router.post("", response_model=ThingOut, status_code=201)
      def create_thing(payload: ThingCreate, db: Db):
          obj = Thing(name=payload.name)
          db.add(obj); db.commit(); db.refresh(obj)
          return obj

      @router.get("/{thing_id}", response_model=ThingOut)
      def get_thing(thing_id: int, db: Db):
          obj = db.get(Thing, thing_id)
          if obj is None:
              raise HTTPException(status_code=404)
          return obj

      @router.delete("/{thing_id}", status_code=204)
      def delete_thing(thing_id: int, db: Db):
          obj = db.get(Thing, thing_id)
          if obj is not None:
              db.delete(obj); db.commit()

Register in app/main.py: `app.include_router(things.router, prefix="/api")` so paths are `/api/things`.
Run the app only via the harness (verify_behavior boots uvicorn for you); never start uvicorn yourself.

- Tests: pytest + FastAPI's in-process TestClient (add `pytest` and `httpx` to requirements; no
  server, no ports). Assert EXACT values the real code computed — status, fields, counts — never
  just `is not None`:

      from fastapi.testclient import TestClient
      from app.main import app

      client = TestClient(app)

      def test_create_then_get():
          r = client.post("/api/things", json={"name": "x"})
          assert r.status_code == 201
          tid = r.json()["id"]
          got = client.get(f"/api/things/{tid}")
          assert got.status_code == 200
          assert got.json()["name"] == "x"

      def test_get_missing_is_404():
          assert client.get("/api/things/99999").status_code == 404

  Run `python -m pytest -x` FROM THE PROJECT ROOT — tests import `app.*`, so a wrong working
  directory (or a missing `tests/__init__.py`) kills the whole suite at collection time with
  ImportError before a single test runs. One bad import line = zero tests executed.

- If the goal asks for a DASHBOARD page (HTML, not JSON), serve an actual page — a JSON API does not
  satisfy it. Either Jinja2 templates or an inline `HTMLResponse`:

      from fastapi.responses import HTMLResponse

      @app.get("/", response_class=HTMLResponse)   # or "/dashboard"
      def dashboard():
          cats = service.categories_with_counts()   # call the REAL pipeline, not placeholders
          rows = "".join(f"<tr><td>{c['name']}</td><td>{c['count']}</td></tr>" for c in cats)
          return f"<!doctype html><html><body><h1>Dashboard</h1><table>{rows}</table></body></html>"

  (For Jinja2: `templates = Jinja2Templates(directory="templates")` and
  `return templates.TemplateResponse("dashboard.html", {"request": request, "cats": cats})`.)
  The page MUST render real computed data (a bill amount, a category count), not a hardcoded shell.
  Test it: `client.get("/")` is 200, `content-type` starts with `text/html`, and the body contains a
  real value (`assert "utilities" in r.text`). A dashboard that 404s or returns JSON is an unmet concern.
