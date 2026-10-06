"""Prints a GitHub Actions error annotation for each failed test in the Gradle JUnit XML reports.

The annotations show on the run summary and through the API, without downloading the report artifact.
"""
import glob
import xml.etree.ElementTree as ET

for report in sorted(glob.glob("**/build/test-results/**/*.xml", recursive=True)):
    suite = ET.parse(report).getroot()
    # The end of the class's output (logs), to show what it was doing when it failed.
    output = "\n".join((suite.findtext("system-out") or "").strip().splitlines()[-15:])
    for case in suite.iter("testcase"):
        for bad in case.findall("failure") + case.findall("error"):
            text = (bad.get("message") or "") + "\n" + (bad.text or "")
            text = "\n".join(text.strip().splitlines()[:25])
            if output:
                text += "\n--- output (last lines) ---\n" + output
            text = text.replace("%", "%25").replace("\r", "").replace("\n", "%0A")
            print(f"::error title={case.get('classname')}.{case.get('name')}::{text}")
