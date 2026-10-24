#!/usr/bin/env python3
"""用真实 PMD 验证构造器豁免不会放过同文件的普通方法。"""

from __future__ import annotations

import subprocess
import tempfile
import unittest
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[3]


class PmdParameterPolicyTest(unittest.TestCase):
    def test_constructor_exemption_keeps_seven_parameter_methods_blocked(self) -> None:
        root_pom = ET.parse(ROOT / "pom.xml").getroot()
        namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
        plugin = next(
            item
            for item in root_pom.findall(".//m:plugin", namespace)
            if item.findtext("m:artifactId", namespaces=namespace) == "maven-pmd-plugin"
        )
        version = plugin.findtext("m:version", namespaces=namespace)
        with tempfile.TemporaryDirectory(prefix="pmd-parameter-policy-") as raw:
            temporary = Path(raw)
            source = temporary / "src/main/java/ParameterPolicyFixture.java"
            source.parent.mkdir(parents=True)
            source.write_text(
                """class ParameterPolicyFixture {
  ParameterPolicyFixture(int a, int b, int c, int d, int e, int f, int g, int h) {}
  void accepted(int a, int b, int c, int d, int e, int f) {}
  void rejected(int a, int b, int c, int d, int e, int f, int g) {}
}
interface MapperPolicyFixture {
  void rejected(int a, int b, int c, int d, int e, int f, int g);
}
record RecordPolicyFixture(int a, int b, int c, int d, int e, int f, int g, int h) {}
interface ParameterPolicyMapper {
  void acceptedFour(int a, int b, int c, int d);
  void rejectedFive(int a, int b, int c, int d, int e);
  void rejectedSix(int a, int b, int c, int d, int e, int f);
  private void acceptedPrivate(int a, int b, int c, int d, int e) {}
}
interface ParameterPolicyService {
  void acceptedFive(int a, int b, int c, int d, int e);
  void rejectedSix(int a, int b, int c, int d, int e, int f);
}
class ConcreteMapper {
  public void rejectedFive(int a, int b, int c, int d, int e) {}
  private void acceptedPrivate(int a, int b, int c, int d, int e) {}
}
class ConcreteService {
  public void rejectedSix(int a, int b, int c, int d, int e, int f) {}
  private void acceptedPrivate(int a, int b, int c, int d, int e, int f) {}
}
""",
                encoding="utf-8",
            )
            pom = temporary / "pom.xml"
            pom.write_text(
                f"""<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>local.governance</groupId><artifactId>parameter-policy</artifactId><version>1</version>
  <properties><maven.compiler.release>21</maven.compiler.release></properties>
  <build><plugins><plugin>
    <groupId>org.apache.maven.plugins</groupId><artifactId>maven-pmd-plugin</artifactId>
    <version>{version}</version>
    <configuration>
      <rulesets><ruleset>{ROOT / 'build/pmd-ruleset.xml'}</ruleset></rulesets>
      <linkXRef>false</linkXRef><targetJdk>21</targetJdk>
    </configuration>
  </plugin></plugins></build>
</project>
""",
                encoding="utf-8",
            )
            result = subprocess.run(
                [str(ROOT / "mvnw"), "-ntp", "-f", str(pom),
                 f"org.apache.maven.plugins:maven-pmd-plugin:{version}:pmd"],
                cwd=ROOT,
                capture_output=True,
                text=True,
                timeout=120,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            report = ET.parse(temporary / "target/pmd.xml").getroot()
            violations = [
                item for item in report.iter()
                if item.tag.rsplit("}", 1)[-1] == "violation"
                and item.get("rule") == "ExcessiveParameterList"
            ]
            self.assertEqual(
                sorted(int(item.attrib["beginline"]) for item in violations), [4, 7]
            )
            for rule, expected_lines in {
                "MapperParameterCount": [12, 13, 21],
                "ServicePublicParameterCount": [18, 25],
            }.items():
                with self.subTest(rule=rule):
                    actual_lines = sorted(
                        int(item.attrib["beginline"]) for item in report.iter()
                        if item.tag.rsplit("}", 1)[-1] == "violation"
                        and item.get("rule") == rule
                    )
                    self.assertEqual(actual_lines, expected_lines)
            processing_errors = [
                item for item in report.iter()
                if item.tag.rsplit("}", 1)[-1] in {"error", "configerror"}
            ]
            self.assertEqual(processing_errors, [])


if __name__ == "__main__":
    unittest.main()
