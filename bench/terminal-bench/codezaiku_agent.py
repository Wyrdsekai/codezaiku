"""Harbor BaseAgent adapter that runs CodeZaiku's real decompose loop on a Terminal-Bench task.

CodeZaiku's Java loop runs on the HOST; its shell/read/edit tools are routed INTO the task container
via docker exec (env-gated CODEZAIKU_EXEC_CONTAINER — see ContainerExec.java). A docker container's
hostname == its short id, so we get the id from inside the container and hand it to CodeZaiku.
"""
import asyncio
import os
from pathlib import Path
from typing import override

from harbor.agents.base import BaseAgent
from harbor.environments.base import BaseEnvironment
from harbor.models.agent.context import AgentContext

CODEZAIKU_DIR = os.environ.get("CODEZAIKU_DIR", ""${CP_WORK:-/opt/codezaiku}"/codezaiku")
LLM_URL = os.environ.get("CODEZAIKU_LLM_URL", "http://localhost:8200")
MAX_TURNS = os.environ.get("CODEZAIKU_MAX_TURNS", "60")


class CodeZaikuAgent(BaseAgent):
    SUPPORTS_ATIF: bool = False
    SUPPORTS_WINDOWS: bool = False

    @staticmethod
    @override
    def name() -> str:
        return "codezaiku"

    @override
    def version(self) -> str:
        return "0.1.0"

    @override
    async def setup(self, environment: BaseEnvironment) -> None:
        return

    @override
    async def run(self, instruction: str, environment: BaseEnvironment,
                  context: AgentContext) -> None:
        # container short-id (== hostname for a standard docker container) + working dir
        cid = (await environment.exec("hostname")).stdout.strip()
        try:
            wd = (await environment.exec("pwd")).stdout.strip() or "/app"
        except Exception:
            wd = "/app"

        logs = Path(self.logs_dir)
        logs.mkdir(parents=True, exist_ok=True)
        goal = logs / "goal.md"
        goal.write_text(instruction)
        root = logs / "root"
        root.mkdir(exist_ok=True)
        log = logs / "codezaiku.log"

        env = dict(os.environ)
        env["CODEZAIKU_EXEC_CONTAINER"] = cid
        env["CODEZAIKU_EXEC_WORKDIR"] = wd
        env["CUDA_DEVICE_ORDER"] = "PCI_BUS_ID"
        env["CODEZAIKU_SELFVERIFY"] = "on"

        cmd = (
            f"cd {CODEZAIKU_DIR} && ./gradlew -q :core:run "
            f'--args="decompose {root} @{goal} {LLM_URL} {MAX_TURNS} none"'
        )
        self.logger.info(f"CodeZaiku on container={cid} wd={wd}")
        with open(log, "wb") as lf:
            proc = await asyncio.create_subprocess_shell(
                cmd, env=env, stdout=lf, stderr=asyncio.subprocess.STDOUT)
            await proc.wait()
        self.logger.info(f"CodeZaiku finished rc={proc.returncode}")
