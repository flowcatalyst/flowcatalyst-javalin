import { describe, it, expect } from "vitest";
import { decideSpaGate } from "../spaGate.js";

describe("decideSpaGate", () => {
    it("proceeds when the bytes match", () => {
        const html = "<html>same</html>";
        const result = decideSpaGate(html, html, "abc1234", false);
        expect(result.matched).toBe(true);
        expect(result.proceed).toBe(true);
    });

    it("refuses to proceed on a mismatch without the override", () => {
        const result = decideSpaGate("<html>go</html>", "<html>java</html>", "abc1234", false);
        expect(result.matched).toBe(false);
        expect(result.proceed).toBe(false);

        // Mutant check: if decideSpaGate always returned proceed:true, this
        // assertion (not just "matched is false") is what catches it.
    });

    it("proceeds on a mismatch when the caller allows it, and says so", () => {
        const result = decideSpaGate("<html>go</html>", "<html>java</html>", "abc1234", true);
        expect(result.matched).toBe(false);
        expect(result.proceed).toBe(true);
        expect(result.message).toMatch(/SPA revisions differ/);
        expect(result.message).toContain("abc1234");
    });

    it("hashes differ only when the bytes actually differ (not a constant hash)", () => {
        const a = decideSpaGate("<html>one</html>", "<html>one</html>", "", false);
        const b = decideSpaGate("<html>one</html>", "<html>two</html>", "", false);
        expect(a.goHash).toBe(b.goHash); // go side unchanged between the two calls
        expect(a.javaHash).not.toBe(b.javaHash);
    });
});

import { normaliseIndexHtml } from "../spaGate.js";

describe("normaliseIndexHtml", () => {
    it("blanks Vite's per-build asset hashes so two builds of one source match", () => {
        const a = '<script type="module" src="/assets/index-CigTA1gY.js"></script><link href="/assets/index-Bq2x_9Zk.css">';
        const b = '<script type="module" src="/assets/index-vE2h-lD0.js"></script><link href="/assets/index-Zz00aaBB.css">';
        expect(normaliseIndexHtml(a)).toBe(normaliseIndexHtml(b));
        expect(decideSpaGate(a, b, "89b195e", false).matched).toBe(true);
    });

    it("still tells a different document apart", () => {
        const a = '<script src="/assets/index-CigTA1gY.js"></script><title>A</title>';
        const b = '<script src="/assets/index-vE2h-lD0.js"></script><title>B</title>';
        expect(decideSpaGate(a, b, "x", false).matched).toBe(false);
    });
});

