"""PostToolUse hook: Windows toast when a new playable mod jar has been built.

Fires only when the shell command ran a Gradle build/jar task, Gradle reported
BUILD SUCCESSFUL, and build/libs contains a jar written within the last 10 minutes.

The toast includes a brief "what to expect" summary taken from
.claude/next-build-summary.txt (written by Claude before each build). If that file
is missing or older than the previous notification, the summary falls back to the
subjects of git commits made since the last notified build.
"""
import json
import os
import subprocess
import sys
import time

PROJECT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SUMMARY_FILE = os.path.join(PROJECT, ".claude", "next-build-summary.txt")
STATE_FILE = os.path.join(PROJECT, ".claude", "hooks", ".last_notified.json")


def load_state():
    try:
        with open(STATE_FILE, encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return {}


def git(*args):
    try:
        r = subprocess.run(["git", "-C", PROJECT, *args], capture_output=True, text=True, timeout=10)
        return r.stdout.strip() if r.returncode == 0 else ""
    except Exception:
        return ""


def build_summary(state, jar_mtime):
    # 1) Explicit summary written for this build.
    if os.path.isfile(SUMMARY_FILE) and os.path.getmtime(SUMMARY_FILE) > state.get("time", 0):
        with open(SUMMARY_FILE, encoding="utf-8") as f:
            text = " ".join(line.strip("-• \t") for line in f.read().splitlines() if line.strip())
        if text:
            return text
    # 2) Commit subjects since the last notified commit.
    last = state.get("commit")
    rng = f"{last}..HEAD" if last and git("cat-file", "-t", last) == "commit" else "-5"
    subjects = git("log", "--format=%s", rng) if rng != "-5" else git("log", "-5", "--format=%s")
    if subjects:
        return "; ".join(subjects.splitlines()[:4])
    return "Uncommitted work-in-progress build."


def main():
    try:
        payload = json.load(sys.stdin)
    except Exception:
        return
    cmd = str(payload.get("tool_input", {}).get("command", ""))
    if "gradlew" not in cmd or not any(t in cmd for t in ("build", "jar")):
        return
    resp = payload.get("tool_response", {})
    out = resp if isinstance(resp, str) else json.dumps(resp)
    if "BUILD SUCCESSFUL" not in out:
        return
    libs = os.path.join(PROJECT, "build", "libs")
    if not os.path.isdir(libs):
        return
    jars = [os.path.join(libs, f) for f in os.listdir(libs)
            if f.endswith(".jar") and "sources" not in f]
    if not jars:
        return
    newest = max(jars, key=os.path.getmtime)
    mtime = os.path.getmtime(newest)
    if time.time() - mtime > 600:
        return
    state = load_state()
    if state.get("jar_mtime") == mtime:
        return  # already announced this exact jar
    name = os.path.basename(newest)
    summary = build_summary(state, mtime)
    short = summary if len(summary) <= 230 else summary[:227] + "..."

    # Pass text through environment variables so quotes in the summary cannot break the script.
    env = dict(os.environ, TOAST_TITLE=f"Emergence build ready: {name}", TOAST_BODY=short)
    ps = (
        "[Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime] > $null;"
        "$t = [Windows.UI.Notifications.ToastNotificationManager]::GetTemplateContent([Windows.UI.Notifications.ToastTemplateType]::ToastText02);"
        "$t.GetElementsByTagName('text').Item(0).AppendChild($t.CreateTextNode($env:TOAST_TITLE)) > $null;"
        "$t.GetElementsByTagName('text').Item(1).AppendChild($t.CreateTextNode($env:TOAST_BODY)) > $null;"
        "$app = '{1AC14E77-02E7-4E5D-B744-2EB1AE5198B7}\\WindowsPowerShell\\v1.0\\powershell.exe';"
        "[Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier($app).Show([Windows.UI.Notifications.ToastNotification]::new($t))"
    )
    subprocess.run(["powershell", "-NoProfile", "-NonInteractive", "-Command", ps],
                   capture_output=True, timeout=20, env=env)

    try:
        with open(STATE_FILE, "w", encoding="utf-8") as f:
            json.dump({"time": time.time(), "jar_mtime": mtime, "commit": git("rev-parse", "HEAD")}, f)
    except Exception:
        pass
    print(json.dumps({"systemMessage": f"New playable build: build/libs/{name}\nWhat to expect: {summary}"}))


if __name__ == "__main__":
    main()
