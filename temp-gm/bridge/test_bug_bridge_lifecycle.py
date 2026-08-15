import http.client
import json
import tempfile
import threading
import unittest
from pathlib import Path

import temp_bug_harness as bug
import temp_gm_bridge as bridge


class BridgeBugLifecycleTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        bridge.BUG_STORE = bug.BugReportStore(Path(cls.tmp.name))
        cls.server = bridge.ThreadingHTTPServer(("127.0.0.1", 0), bridge.Handler)
        cls.port = cls.server.server_address[1]
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown(); cls.server.server_close(); cls.thread.join(timeout=2); cls.tmp.cleanup()

    def request(self, method, path, body=None):
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=5)
        payload = None if body is None else json.dumps(body).encode()
        headers = {"Content-Type":"application/json"} if payload is not None else {}
        conn.request(method, path, body=payload, headers=headers)
        resp = conn.getresponse(); raw=resp.read(); conn.close()
        return resp.status, json.loads(raw.decode())

    def create(self, **extra):
        body={"description":"Po kliknięciu Kontynuuj nic się nie dzieje.","include_logcat":False,"include_screenshot":False,
              "build":{"versionName":"1","versionCode":1,"buildSha":"abc"},"route":"SAVES",
              "environment":{"deviceModel":"SM-S921B","androidSdk":36}}
        body.update(extra)
        st,res=self.request("POST","/bug",body); self.assertEqual(st,201); return res["reportUid"]

    def test_BRIDGE_BUG_01_list_pending(self):
        uid=self.create(); st,res=self.request("GET","/bugs"); self.assertEqual(st,200); self.assertGreaterEqual(res["pendingCount"],1); self.assertFalse(res["canonicalMutation"])
    def test_BRIDGE_BUG_02_load_detail(self):
        uid=self.create(); st,res=self.request("GET",f"/bugs/{uid}"); self.assertEqual(st,200); self.assertEqual(res["report"]["reportUid"],uid)
    def test_BRIDGE_BUG_03_preview(self):
        uid=self.create(); st,res=self.request("GET",f"/bugs/{uid}/preview"); self.assertEqual(st,200); self.assertIn("USER REPORT",res["issuePreview"])
    def test_BRIDGE_BUG_04_preview_no_auth(self):
        uid=self.create(); self.request("GET",f"/bugs/{uid}/preview"); _,detail=self.request("GET",f"/bugs/{uid}"); self.assertFalse(detail["report"]["github"]["submissionAuthorized"])
    def test_BRIDGE_BUG_05_duplicate_roundtrip(self):
        uid=self.create(); _,detail=self.request("GET",f"/bugs/{uid}"); fp=detail["report"]["duplicateFingerprint"]
        st,res=self.request("POST",f"/bugs/{uid}/duplicates",{"candidates":[{"issueNumber":7,"title":"same","url":"https://example/7","fingerprint":fp}]})
        self.assertEqual(st,200); self.assertEqual(res["report"]["duplicateCandidates"][0]["issueNumber"],7)
    def test_BRIDGE_BUG_06_keep_pending_no_auth(self):
        uid=self.create(); st,res=self.request("POST",f"/bugs/{uid}/decision",{"decision":"KEEP_PENDING"}); self.assertEqual(st,200); self.assertEqual(res["report"]["submissionState"],"LOCAL_PENDING"); self.assertFalse(res["report"]["github"]["submissionAuthorized"])
    def test_BRIDGE_BUG_07_cancel_no_auth(self):
        uid=self.create(); st,res=self.request("POST",f"/bugs/{uid}/cancel",{}); self.assertEqual(st,200); self.assertEqual(res["report"]["submissionState"],"CANCELLED")
    def test_BRIDGE_BUG_08_confirm_new_issue_explicit_auth(self):
        uid=self.create(); st,res=self.request("POST",f"/bugs/{uid}/decision",{"decision":"CONFIRM_NEW_ISSUE"}); self.assertEqual(st,200); self.assertTrue(res["report"]["github"]["submissionAuthorized"])
    def test_BRIDGE_BUG_09_authorization_one_shot(self):
        uid=self.create(); self.request("POST",f"/bugs/{uid}/decision",{"decision":"CONFIRM_NEW_ISSUE"})
        s1,r1=self.request("POST",f"/bugs/{uid}/submission-authorization",{"kind":"NEW_ISSUE"}); s2,r2=self.request("POST",f"/bugs/{uid}/submission-authorization",{"kind":"NEW_ISSUE"})
        self.assertEqual(s1,200); self.assertTrue(r1["allowed"]); self.assertEqual(s2,409); self.assertFalse(r2["allowed"])
    def test_BRIDGE_BUG_10_restart_does_not_invent_auth(self):
        uid=self.create(); self.request("POST",f"/bugs/{uid}/decision",{"decision":"KEEP_PENDING"})
        bridge.BUG_STORE=bug.BugReportStore(Path(self.tmp.name)); st,res=self.request("POST",f"/bugs/{uid}/submission-authorization",{"kind":"NEW_ISSUE"}); self.assertEqual(st,409); self.assertFalse(res["allowed"])
    def test_BRIDGE_BUG_11_mark_submitted(self):
        uid=self.create(); self.request("POST",f"/bugs/{uid}/decision",{"decision":"CONFIRM_NEW_ISSUE"}); self.request("POST",f"/bugs/{uid}/submission-authorization",{"kind":"NEW_ISSUE"})
        st,res=self.request("POST",f"/bugs/{uid}/submitted",{"issueNumber":11,"issueUrl":"https://example/11"}); self.assertEqual(st,200); self.assertEqual(res["report"]["submissionState"],"SUBMITTED")
    def test_BRIDGE_BUG_12_mark_linked_duplicate(self):
        uid=self.create(); _,detail=self.request("GET",f"/bugs/{uid}"); fp=detail["report"]["duplicateFingerprint"]
        self.request("POST",f"/bugs/{uid}/duplicates",{"candidates":[{"issueNumber":12,"title":"d","url":"https://example/12","fingerprint":fp}]})
        self.request("POST",f"/bugs/{uid}/decision",{"decision":"CONFIRM_LINK_DUPLICATE","targetIssueNumber":12})
        s1,r1=self.request("POST",f"/bugs/{uid}/submission-authorization",{"kind":"LINK_DUPLICATE"}); self.assertEqual(s1,200); self.assertTrue(r1["allowed"])
        st,res=self.request("POST",f"/bugs/{uid}/linked-duplicate",{"issueNumber":12,"issueUrl":"https://example/12"}); self.assertEqual(st,200); self.assertEqual(res["report"]["submissionState"],"LINKED_DUPLICATE")
    def test_BRIDGE_BUG_13_offline_remains_pending(self):
        uid=self.create(); _,res=self.request("GET",f"/bugs/{uid}"); self.assertEqual(res["report"]["submissionState"],"LOCAL_PENDING"); self.assertEqual(res["report"]["DEVICE-CAPTURED"]["llamaState"],"OFFLINE")
    def test_BRIDGE_BUG_14_logcat_unavailable_valid(self):
        uid=self.create(include_logcat=True,packageName="invalid.package.zzz"); _,res=self.request("GET",f"/bugs/{uid}"); self.assertEqual(res["report"]["submissionState"],"LOCAL_PENDING"); self.assertIn(res["report"]["DEVICE-CAPTURED"]["logcat"]["status"],["UNAVAILABLE","SKIPPED"])
    def test_BRIDGE_BUG_15_screenshot_no_consent(self):
        uid=self.create(include_screenshot=True,screenshotApproved=False,screenshotReference="/tmp/a.png"); _,res=self.request("GET",f"/bugs/{uid}"); shot=res["report"]["DEVICE-CAPTURED"]["screenshot"]; self.assertFalse(shot["userApproved"]); self.assertEqual(shot["reference"],"")
    def test_BRIDGE_BUG_16_screenshot_with_consent(self):
        uid=self.create(include_screenshot=True,screenshotApproved=True,screenshotReference="screen-approved.png"); _,res=self.request("GET",f"/bugs/{uid}"); shot=res["report"]["DEVICE-CAPTURED"]["screenshot"]; self.assertTrue(shot["userApproved"]); self.assertEqual(shot["reference"],"screen-approved.png")
    def test_BRIDGE_BUG_17_cancel_delete_explicit(self):
        uid=self.create(); self.request("POST",f"/bugs/{uid}/cancel",{}); s0,_=self.request("DELETE",f"/bugs/{uid}"); self.assertEqual(s0,400); s1,r1=self.request("DELETE",f"/bugs/{uid}?confirm=true"); self.assertEqual(s1,200); self.assertTrue(r1["deleted"])
    def test_BRIDGE_BUG_18_unknown_id_fail_closed(self):
        st,res=self.request("GET","/bugs/bug-does-not-exist"); self.assertEqual(st,404); self.assertFalse(res["canonicalMutation"])
    def test_BRIDGE_BUG_19_malformed_decision_fail_closed(self):
        uid=self.create(); st,res=self.request("POST",f"/bugs/{uid}/decision",{"decision":"PUBLISH_NOW"}); self.assertEqual(st,400); self.assertFalse(res["canonicalMutation"])
    def test_BRIDGE_BUG_20_canonical_mutation_false_lifecycle(self):
        uid=self.create(); calls=[("GET",f"/bugs/{uid}",None),("GET",f"/bugs/{uid}/preview",None),("POST",f"/bugs/{uid}/retry",{}),("POST",f"/bugs/{uid}/decision",{"decision":"KEEP_PENDING"})]
        for method,path,body in calls:
            st,res=self.request(method,path,body); self.assertLess(st,500); self.assertFalse(res["canonicalMutation"])

if __name__ == '__main__': unittest.main(verbosity=2)
