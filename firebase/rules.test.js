import { readFileSync } from "node:fs";
import { afterAll, beforeAll, beforeEach, describe, test } from "vitest";
import { assertFails, assertSucceeds, initializeTestEnvironment } from "@firebase/rules-unit-testing";
import { collection, deleteDoc, doc, getDoc, getDocs, setDoc } from "firebase/firestore";

let testEnv;

beforeAll(async () => {
  testEnv = await initializeTestEnvironment({
    projectId: "demo-foodsense",
    firestore: { rules: readFileSync("firestore.rules", "utf8") },
  });
});

afterAll(() => testEnv.cleanup());
beforeEach(() => testEnv.clearFirestore());

const productsOf = (db, uid) => collection(db, "users", uid, "products");

describe("users/{userId}/products", () => {
  test("owner_ownProduct_canWriteAndRead", async () => {
    const alice = testEnv.authenticatedContext("alice").firestore();
    const ref = doc(productsOf(alice, "alice"), "p1");

    await assertSucceeds(setDoc(ref, { name: "Leche", updatedAt: 1, items: [] }));
    await assertSucceeds(getDoc(ref));
  });

  test("owner_ownCollection_canList", async () => {
    const alice = testEnv.authenticatedContext("alice").firestore();

    await assertSucceeds(getDocs(productsOf(alice, "alice")));
  });

  test("otherUser_someoneElsesProducts_cannotRead", async () => {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), "users/alice/products/p1"), { name: "Leche" });
    });
    const bob = testEnv.authenticatedContext("bob").firestore();

    await assertFails(getDoc(doc(productsOf(bob, "alice"), "p1")));
    await assertFails(getDocs(productsOf(bob, "alice")));
  });

  test("otherUser_someoneElsesProducts_cannotWriteOrDelete", async () => {
    const bob = testEnv.authenticatedContext("bob").firestore();

    await assertFails(setDoc(doc(productsOf(bob, "alice"), "p1"), { name: "hack" }));
    await assertFails(deleteDoc(doc(productsOf(bob, "alice"), "p1")));
  });

  test("unauthenticated_anyProducts_isDenied", async () => {
    const anon = testEnv.unauthenticatedContext().firestore();

    await assertFails(getDocs(productsOf(anon, "alice")));
    await assertFails(setDoc(doc(productsOf(anon, "alice"), "p1"), { name: "hack" }));
  });
});
