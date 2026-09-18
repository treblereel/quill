import unittest

from quill_crysknife_quality import SCOPED_GENERATOR, SERVICE_DESCRIPTOR, evaluate


def result(content):
    return {"content": content, "is_error": False}


class CrysknifeQualityTest(unittest.TestCase):

    def valid_calls(self):
        return {
            "constructor_dependencies": result({"metrics": {"fan_in": 2}, "depended_by": [
                {"class": "io.crysknife.ApplicationProcessor", "kind": "CONSTRUCTS",
                 "evidence_lines": [113]},
                {"class": "io.crysknife.AfterBurnFactoryProcessor", "kind": "CONSTRUCTS",
                 "evidence_lines": [84]},
            ]}),
            "constructor_position": result({"selected": {
                "kind": "CONSTRUCTOR", "parameter_count": 2}}),
            "service_descriptor": result({"descriptors": [{
                "file": SERVICE_DESCRIPTOR, "order_can_affect_execution": True,
                "providers": [{"provider": value} for value in [
                    "io.crysknife.ApplicationProcessor",
                    "io.crysknife.AfterBurnFactoryProcessor",
                    "io.crysknife.BeanManagerGeneratorProcessor"]]}]}),
            "deleted_entities": result({"entities": [
                {"target": SCOPED_GENERATOR, "current": False,
                 "historical": True, "deleted": True},
                {"target": "TemplatedGenerator.java", "current": False,
                 "historical": True, "deleted": True}]}),
            "dependencies": result({"dependencies": [
                {"group": "com.google.guava", "artifact": "guava",
                 "directness": "direct"},
                {"group": "com.google.guava", "artifact": "failureaccess",
                 "directness": "transitive"}]}),
            "external_members": result({"symbols": [
                {"name": "builder", "class_name": "com.google.common.collect.ImmutableList"},
                {"name": "builderWithExpectedSize",
                 "class_name": "com.google.common.collect.ImmutableList"}]}),
        }

    def test_accepts_expected_contract(self):
        self.assertEqual([], evaluate(self.valid_calls()))

    def test_reports_semantic_regression(self):
        calls = self.valid_calls()
        calls["constructor_dependencies"]["content"]["metrics"]["fan_in"] = 0
        calls["external_members"]["content"]["symbols"].append({
            "name": "builder", "class_name": "com.google.common.collect.ImmutableList$Builder"})

        failures = evaluate(calls)

        self.assertTrue(any("fan_in" in failure for failure in failures))
        self.assertTrue(any("external_members" in failure for failure in failures))


if __name__ == "__main__":
    unittest.main()
