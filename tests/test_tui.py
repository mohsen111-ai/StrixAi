from pathlib import Path

from strix.config import parse_config
from strix.tui import PermissionScreen, StrixApp

from .conftest import CFG, call, reply, script


async def test_tui_session_end_to_end(tmp_path):
    cfg = parse_config(CFG)
    cfg.settings.mode = "auto-edit"
    fn = script(reply("writing", call("write_file", path="hello.py", content="print('hi')\n")), reply("All done."))
    app = StrixApp(cfg, tmp_path, chat_fn=fn, splash=False)
    async with app.run_test(size=(120, 36)) as pilot:
        await pilot.pause()
        await pilot.click("#prompt")
        await pilot.press(*"/help", "enter")
        await pilot.pause()
        await pilot.press(*"make hello", "enter")
        for _ in range(40):
            await pilot.pause(0.05)
            if not app.busy:
                break
        assert (tmp_path / "hello.py").exists()
        text = "\n".join(str(l.text) for l in app.query_one("#stream").lines)
        assert "write_file" in text and "All done." in text and "Commands" in text



async def test_permission_modal_returns_answer(tmp_path):
    cfg = parse_config(CFG)
    cfg.settings.mode = "ask"
    fn = script(reply("", call("write_file", path="z.txt", content="1\n")), reply("ok"))
    app = StrixApp(cfg, tmp_path, chat_fn=fn, splash=False)
    async with app.run_test(size=(120, 36)) as pilot:
        await pilot.press(*"write z", "enter")
        for _ in range(40):
            await pilot.pause(0.05)
            if isinstance(app.screen, PermissionScreen):
                break
        assert isinstance(app.screen, PermissionScreen)
        await pilot.press("y")
        for _ in range(40):
            await pilot.pause(0.05)
            if not app.busy:
                break
        assert (tmp_path / "z.txt").exists()


async def test_splash_dismisses(tmp_path):
    app = StrixApp(parse_config(CFG), tmp_path)
    async with app.run_test(size=(100, 30)) as pilot:
        await pilot.pause(2.2)
        assert app.screen.__class__.__name__ != "SplashScreen"
