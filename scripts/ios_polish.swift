import XCTest
final class WhumpoPolish: XCTestCase {
 let app=XCUIApplication(bundleIdentifier:"com.whumpo.expomock")
 func el(_ label:String)->XCUIElement {if label=="Ask your Partner, draft only" || label=="Support request draft" {return app.textViews.matching(NSPredicate(format:"label BEGINSWITH %@",label)).firstMatch};let q=app.descendants(matching:.any).matching(NSPredicate(format:"label == %@",label));for e in q.allElementsBoundByIndex.reversed(){if e.isHittable{return e}};return q.firstMatch}
 func tap(_ label:String){let e=el(label);XCTAssertTrue(e.waitForExistence(timeout:15),label);Thread.sleep(forTimeInterval:0.4);print("TAP \(label) frame=\(e.frame) hittable=\(e.isHittable)");e.coordinate(withNormalizedOffset:CGVector(dx:0.5,dy:0.5)).press(forDuration:0.18);Thread.sleep(forTimeInterval:0.7)}
 func capture(_ name:String){Thread.sleep(forTimeInterval:0.7);let a=XCTAttachment(screenshot:XCUIScreen.main.screenshot());a.name=name;a.lifetime = .keepAlways;add(a)}
 func boot(){app.activate();let spring=XCUIApplication(bundleIdentifier:"com.apple.springboard");if spring.buttons["Open"].waitForExistence(timeout:2){spring.buttons["Open"].tap()};if app.buttons["Open"].exists{app.buttons["Open"].tap()};XCTAssertTrue(el("Today").waitForExistence(timeout:40))}
 func focus(_ field:XCUIElement){Thread.sleep(forTimeInterval:1);field.tap();if !app.keyboards.firstMatch.waitForExistence(timeout:3){field.coordinate(withNormalizedOffset:CGVector(dx:0.35,dy:0.5)).tap()};XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout:5))}
 func testKeyboardFlow(){
  continueAfterFailure=false;boot();tap("Today");let field=el("Ask your Partner, draft only");XCTAssertTrue(field.waitForExistence(timeout:20));capture("K-02-today-keyboard-closed");print("MOTION_OPEN_BEGIN \(Date().timeIntervalSince1970)");focus(field);field.typeText("Review the studio");Thread.sleep(forTimeInterval:1)
  XCTAssertFalse(el("More").exists);XCTAssertFalse(el("Hi, Alex").exists);XCTAssertFalse(el("Next · Website draft").exists);XCTAssertTrue(app.staticTexts["Today"].exists)
  let org=el("Switch organization, Studio North"),menu=el("Open Partner history"),cta=el("Review 2 decisions"),send=el("Create draft in Partner");XCTAssertEqual(org.frame.midY,menu.frame.midY,accuracy:1);XCTAssertGreaterThanOrEqual(field.frame.minY-cta.frame.maxY,16);XCTAssertEqual(send.frame.width,48,accuracy:1);XCTAssertEqual(send.frame.height,48,accuracy:1);capture("K-01-today-keyboard-open")
  print("MOTION_CLOSE_BEGIN \(Date().timeIntervalSince1970)");app.swipeDown();if app.keyboards.firstMatch.exists{app.coordinate(withNormalizedOffset:CGVector(dx:0.5,dy:0.22)).tap()};Thread.sleep(forTimeInterval:1)
  // A downward interactive drag on the card body dismisses the keyboard.
  if app.keyboards.firstMatch.exists{app.scrollViews.firstMatch.swipeDown()};XCTAssertTrue(el("Hi, Alex").waitForExistence(timeout:8));XCTAssertTrue(el("Next · Website draft").exists);XCTAssertTrue(el("More").exists);capture("K-02-today-keyboard-closed")
  tap("Create draft in Partner");XCTAssertTrue(el("Draft · not sent").waitForExistence(timeout:10));capture("L2-32-draft-bubble");let p=el("Ask your Partner, draft only");focus(p);p.typeText("Review service descriptions");XCTAssertFalse(el("More").exists);capture("K-03-partner-keyboard-open");tap("Create draft in Partner")
  tap("More");XCTAssertTrue(el("Open settings").waitForExistence(timeout:8));tap("Open settings");XCTAssertTrue(el("Help").exists);capture("K-06-settings-help-button");tap("Help");tap("Contact support");let support=el("Support request draft");XCTAssertTrue(support.waitForExistence(timeout:10));focus(support);support.typeText("Help reviewing a draft");capture("K-04-support-keyboard-open");tap("Save support draft in Partner");XCTAssertTrue(el("Draft · not sent").waitForExistence(timeout:10));capture("L2-35-support-draft")
 }
 func testKeyboardMotionOnly(){
  continueAfterFailure=false;boot();let field=el("Ask your Partner, draft only");focus(field);field.typeText("Review the studio");Thread.sleep(forTimeInterval:1);capture("K-01-today-keyboard-open");app.swipeDown();Thread.sleep(forTimeInterval:1);XCTAssertTrue(el("Hi, Alex").waitForExistence(timeout:8));XCTAssertTrue(el("Next · Website draft").exists);capture("K-02-today-keyboard-closed");focus(field);field.typeText("\n");XCTAssertTrue(el("Draft · not sent").waitForExistence(timeout:10))
 }
 func testMotionSurfaces(){
  continueAfterFailure = false
  boot()
  tap("Partner")
  XCTAssertTrue(el("Website launch").exists)
  tap("Pinned tab, Work")
  XCTAssertTrue(el("A clearer next step.").exists)
  tap("More")
  XCTAssertTrue(el("Open settings").exists)
  let work = el("Pinned tab, Work")
  work.press(forDuration: 0.65)
  capture("motion-tab-tooltip")
  XCTAssertTrue(el("Open settings").exists)
  tap("Today")
  tap("Open Partner history")
  XCTAssertTrue(el("Weekly approvals").exists)
  capture("motion-history")
  let start = app.coordinate(withNormalizedOffset: CGVector(dx: 0.8, dy: 0.39))
  let finish = app.coordinate(withNormalizedOffset: CGVector(dx: 0.2, dy: 0.39))
  start.press(forDuration: 0.12, thenDragTo: finish)
  XCTAssertTrue(el("Reviews, 2 approvals").waitForExistence(timeout: 8))
  tap("Review 2 decisions")
  tap("Website introduction")
  tap("Approve change")
  XCTAssertTrue(el("Cancel").exists)
  capture("motion-approval-sheet")
  tap("Approve")
  XCTAssertTrue(el("Approved, awaiting publish step").waitForExistence(timeout: 8))
  capture("motion-approval-toast")
 }
 func testKeyboardLarge(){
  continueAfterFailure = false
  boot()
  let field = el("Ask your Partner, draft only")
  let card = app.descendants(matching: .any)["approval-main-card"].firstMatch
  let summary = app.descendants(matching: .any)["today-keyboard-summary"].firstMatch
  XCTAssertTrue(card.exists)
  focus(field)
  field.typeText("Review this")
  XCTAssertTrue(summary.waitForExistence(timeout: 8))
  let review = app.buttons["Review 2 decisions"]
  XCTAssertTrue(review.isHittable)
  XCTAssertGreaterThanOrEqual(field.frame.minY - review.frame.maxY, 16)
  XCTAssertFalse(el("More").exists)
  XCTAssertFalse(el("Hi, Alex").exists)
  XCTAssertFalse(card.exists)
  capture("K-07-today-keyboard-large")
  let start = app.coordinate(withNormalizedOffset: CGVector(dx: 0.55, dy: 0.36))
  let finish = app.coordinate(withNormalizedOffset: CGVector(dx: 0.55, dy: 0.76))
  start.press(forDuration: 0.1, thenDragTo: finish)
  XCTAssertTrue(card.waitForExistence(timeout: 8))
  XCTAssertFalse(summary.exists)
  XCTAssertTrue(el("Hi, Alex").exists)
  XCTAssertTrue((field.value as? String ?? "").contains("Review this"))
  capture("K-08-rear-card-large")
  focus(field)
  XCTAssertTrue(summary.waitForExistence(timeout: 8))
  review.tap()
  XCTAssertTrue(el("Needs your decision").waitForExistence(timeout: 10))
 }
 func testPolishFlow(){
  continueAfterFailure=false;boot();tap("Today");let field=el("Ask your Partner, draft only");XCTAssertTrue(field.waitForExistence(timeout:20));focus(field);field.typeText("Review the studio");Thread.sleep(forTimeInterval:1)
  XCTAssertFalse(el("More").exists);let send=el("Create draft in Partner");XCTAssertEqual(field.frame.height,48,accuracy:1);XCTAssertEqual(send.frame.width,48,accuracy:1);XCTAssertEqual(send.frame.height,48,accuracy:1);XCTAssertEqual(field.frame.midY,send.frame.midY,accuracy:1);capture("P-01-typing-send-button")
  let disc=app.descendants(matching:.any).matching(identifier:"composer-send-disc").firstMatch
  if disc.exists{XCTAssertEqual(disc.frame.width,40,accuracy:1);XCTAssertEqual(disc.frame.height,40,accuracy:1)}
  field.typeText(" with a clear and welcoming introduction for a small design studio.");Thread.sleep(forTimeInterval:0.5);XCTAssertGreaterThan(field.frame.height,48);field.typeText("\nAdd a clear opening.\nKeep the voice warm.\nUse short sentences.\nCheck the service list.\nKeep this as a draft.");XCTAssertTrue((field.value as? String ?? "").contains("Keep this as a draft."));XCTAssertGreaterThan(field.frame.height,100);capture("P-10-multiline-composer")
  tap("Create draft in Partner");XCTAssertTrue(el("More").waitForExistence(timeout:10));tap("Partner");let p=el("Ask your Partner, draft only");XCTAssertTrue(p.waitForExistence(timeout:15));focus(p);p.typeText("Review the service descriptions");XCTAssertFalse(el("More").exists);capture("P-02-partner-typing");tap("Create draft in Partner");capture("L2-32-draft-bubble")
  tap("Today");tap("Reviews, 2 approvals");tap("Website introduction");tap("Approve change");XCTAssertTrue(el("Cancel").waitForExistence(timeout:10));XCTAssertFalse(el("Close dialog").exists);capture("P-09-approval-sheet");tap("Approve");Thread.sleep(forTimeInterval:3);XCTAssertTrue(el("Approved, awaiting publish step").exists);capture("L2-33-approved-detail")
  tap("Back");XCTAssertTrue(el("Needs your decision").waitForExistence(timeout:10));XCTAssertTrue(el("Approved").exists);capture("P-03-reviews-grouped");tap("Back");if !el("Review service descriptions").waitForExistence(timeout:4){tap("Back")};XCTAssertTrue(el("Review service descriptions").waitForExistence(timeout:8));capture("P-04-today-after-one-approval");capture("L2-34-today-one-approval")
  tap("Review 1 decision");tap("Service descriptions");tap("Approve change");tap("Approve");Thread.sleep(forTimeInterval:3);tap("Back");XCTAssertTrue(el("Nothing needs you right now").waitForExistence(timeout:10));capture("P-06-reviews-empty");tap("Back");if !el("View Activity").waitForExistence(timeout:4){tap("Back")};XCTAssertTrue(el("View Activity").waitForExistence(timeout:8));capture("P-05-today-resolved")
  tap("More");tap("Account");capture("P-07-signout-row");tap("Back");tap("Open settings");XCTAssertTrue(el("Alex Morgan").waitForExistence(timeout:10));capture("P-08-settings-with-account")
 }
 func testLargeReviews(){
  continueAfterFailure=false;boot();tap("Reviews, 2 approvals");tap("Website introduction");tap("Approve change");capture("A-12-approval-sheet-large");tap("Approve");Thread.sleep(forTimeInterval:3);tap("Back");XCTAssertTrue(el("Needs your decision").exists);XCTAssertTrue(el("Approved").exists);capture("A-11-reviews-grouped-large");app.swipeUp();capture("A-13-reviews-approved-large")
 }
 func testConsecutiveApprovals(){
  continueAfterFailure=false;boot();tap("Reviews, 2 approvals");tap("Website introduction");tap("Approve change");tap("Approve");XCTAssertTrue(el("Approved, awaiting publish step").waitForExistence(timeout:5));tap("Back");tap("Service descriptions");tap("Approve change");tap("Approve");XCTAssertTrue(el("Approved, awaiting publish step").waitForExistence(timeout:5));tap("Back");XCTAssertTrue(el("Nothing needs you right now").exists)
 }
 func testRestingApprovalLayout(){
  continueAfterFailure=false;boot();tap("Reviews, 2 approvals");tap("Website introduction");tap("Approve change");tap("Approve");XCTAssertTrue(el("Approved, awaiting publish step").waitForExistence(timeout:8));tap("Back");tap("Back");XCTAssertTrue(el("Review service descriptions").waitForExistence(timeout:8));Thread.sleep(forTimeInterval:3)
  for id in ["approval-main-card","approval-card-body","approval-intrinsic-content","approval-actions"] {let v=app.descendants(matching:.any).matching(identifier:id).firstMatch;print("RESTING_CARD \(id) \(v.frame)")}
  let body=app.descendants(matching:.any).matching(identifier:"approval-card-body").firstMatch,content=app.descendants(matching:.any).matching(identifier:"approval-intrinsic-content").firstMatch
  XCTAssertGreaterThanOrEqual(body.frame.height+0.5,content.frame.height,"The remaining approval must fit its resting card without clipped text")
  capture("L2-34-today-one-approval");capture("P-04-today-after-one-approval")
 }
}
