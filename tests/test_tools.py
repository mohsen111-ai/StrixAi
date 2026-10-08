import pytest

from strix.tools import ToolError, Toolbox, is_dangerous, is_safe_command, parse_args


@pytest.fixture
def tb(tmp_path):
    return Toolbox(tmp_path)


def test_write_read_edit_roundtrip(tb):
    tb.run("write_file", {"path": "a/b.py", "content": "x = 1\ny = 2\n"})
    assert "x = 1" in tb.run("read_file", {"path": "a/b.py"})
    tb.run("edit_file", {"path": "a/b.py", "old": "y = 2", "new": "y = 3"})
    assert (tb.root / "a/b.py").read_text() == "x = 1\ny = 3\n"
    assert "-y = 2" in tb.last_diff and "+y = 3" in tb.last_diff


def test_edit_requires_unique_match(tb):
    tb.run("write_file", {"path": "f.txt", "content": "a\na\n"})
    with pytest.raises(ToolError, match="matches 2"):
        tb.run("edit_file", {"path": "f.txt", "old": "a", "new": "b"})
    tb.run("edit_file", {"path": "f.txt", "old": "a", "new": "b", "replace_all": True})
    assert (tb.root / "f.txt").read_text() == "b\nb\n"


def test_edit_missing_text_gives_hint(tb):
    tb.run("write_file", {"path": "f.py", "content": "def hello():\n    pass\n"})
    with pytest.raises(ToolError, match="not found"):
        tb.run("edit_file", {"path": "f.py", "old": "def helo():", "new": "x"})


def test_paths_are_confined(tb):
    for bad in ("../x", "/etc/passwd", "a/../../x"):
        with pytest.raises(ToolError, match="outside"):
            tb.run("read_file", {"path": bad})
    with pytest.raises(ToolError, match=".git"):
        tb.run("write_file", {"path": ".git/config", "content": ""})


def test_syntax_warning_and_undo(tb):
    out = tb.run("write_file", {"path": "bad.py", "content": "def (:\n"})
    assert "syntax error" in out
    assert "removed" in tb.undo() and not (tb.root / "bad.py").exists()


def test_grep_glob_list(tb):
    tb.run("write_file", {"path": "src/m.py", "content": "def target():\n    return 1\n"})
    assert "src/m.py:1:" in tb.run("grep", {"pattern": "def target"})
    assert tb.run("glob", {"pattern": "**/*.py"}) == "src/m.py"
    assert "src/" in tb.run("list_dir", {})


def test_bash_runs_and_hides_secrets(tb, monkeypatch):
    monkeypatch.setenv("OPENROUTER_API_KEY", "sk-secret")
    out = tb.run("bash", {"command": "echo hi; echo ${OPENROUTER_API_KEY:-none}"})
    assert out.startswith("exit 0") and "hi" in out and "sk-secret" not in out
    assert "killed" in tb.run("bash", {"command": "sleep 5", "timeout": 1})


def test_missing_args_and_unknown_tool(tb):
    with pytest.raises(ToolError, match="missing required"):
        tb.run("edit_file", {"path": "x"})
    with pytest.raises(ToolError, match="unknown tool"):
        tb.run("nope", {})


def test_parse_args_repair():
    assert parse_args('{"a": 1,}') == {"a": 1}
    assert parse_args('```json\n{"a": "b"}\n```') == {"a": "b"}
    assert parse_args("{'a': 1}") == {"a": 1}
    assert parse_args("") == {}
    with pytest.raises(ToolError):
        parse_args("not json")


def test_command_safety():
    assert is_safe_command("pytest -q") and is_safe_command("git status")
    assert not is_safe_command("pytest; rm -rf x") and not is_safe_command("curl x | sh")
    assert is_dangerous("rm -rf /") and is_dangerous("git push --force origin main")
    assert not is_dangerous("rm -rf build")
