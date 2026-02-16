package com.drilldex.drillbackend.licensing;

import com.drilldex.drillbackend.album.Album;
import com.drilldex.drillbackend.beat.Beat;

import com.drilldex.drillbackend.beat.BeatLicense;
import com.drilldex.drillbackend.beat.LicenseType;
import com.drilldex.drillbackend.kit.Kit;
import com.drilldex.drillbackend.pack.Pack;
import com.drilldex.drillbackend.user.User;
import com.lowagie.text.*;
import com.lowagie.text.pdf.*;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.text.NumberFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;


import java.io.OutputStream;


public class LicensePdfGenerator {

    /* ==============================
       Public API
       ============================== */

    public static String generateLicensePdf(User buyer, Beat beat, LicenseTerms terms) throws Exception {
        final String outDir = "licenses";
        Files.createDirectories(Paths.get(outDir));

        final String agreementId = "DL-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        final String filename = outDir + "/license-" + beat.getId() + "-" + System.currentTimeMillis() + ".pdf";

        // Build a verification payload we also embed as text + QR
        String payload = "agreementId=" + agreementId +
                "&type=BEAT" +
                "&beatId=" + beat.getId() +
                "&license=" + terms.getType() +
                "&buyer=" + safe(buyer.getEmail());

        // Assemble content to a buffer so we can fingerprint the bytes (hash)
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        Document doc = PdfDocFactory.newDocument(bos, "Drilldex License", agreementId);

        PdfBranding.PageDecoration event = PdfBranding.pageDecoration(agreementId);
        PdfWriter writer = PdfBranding.newWriter(doc, bos, event);

        Styles styles = Styles.defaultStyles();
        doc.open();

        PdfBranding.addFrontMatter(doc, styles, "DRILLDEX LICENSE AGREEMENT", agreementId);

        // Summary table
        doc.add(Styles.vspace(6));
        doc.add(summaryTableForBeat(buyer, beat, terms, styles));

        // Rights section (driven by LicenseTerms)
        doc.add(Styles.vspace(10));
        section(doc, styles, "1. Grant of License",
                "Licensor grants Licensee a non-exclusive (unless marked Exclusive), worldwide, non-transferable license to use the musical work (“Beat”) under the terms below.");

        doc.add(section(doc, styles, "2. Rights Granted",
                bulletLines(styles.body,
                        "Commercial Use: " + yn(terms.isCommercialUse()),
                        "Max Streams: " + unlimited(terms.getMaxStreams()),
                        "Max Music Videos: " + unlimited(terms.getMaxMusicVideos()),
                        "Radio Broadcast: " + yn(terms.isRadioRights()),
                        "Live Performances: " + yn(terms.isLivePerformanceRights()),
                        "Includes Stems: " + yn(terms.isIncludesStems()),
                        "Exclusive Rights: " + yn(terms.isExclusive())
                )));

        doc.add(section(doc, styles, "3. Term", para(styles.body, "Perpetual unless otherwise agreed in writing.")));
        doc.add(section(doc, styles, "4. Territory", para(styles.body, "Worldwide.")));

        doc.add(section(doc, styles, "5. Restrictions",
                para(styles.body, "Licensee shall not resell, redistribute, or sublicense the Beat in isolation. No use in fingerprinting systems (e.g., Content ID) without explicit written consent.")));

        doc.add(section(doc, styles, "6. Ownership",
                para(styles.body, "Licensor retains all rights in the master and underlying composition. No ownership transfers under this Agreement.")));

        doc.add(section(doc, styles, "7. Credit",
                para(styles.body, "Where technically feasible: “Produced by Drilldex”.")));

        doc.add(section(doc, styles, "8. Payment",
                para(styles.body, "A one‑time licensing fee has been paid. No further royalties are due under this Agreement.")));

        doc.add(section(doc, styles, "9. Warranties",
                para(styles.body, "Licensor warrants it has the right to license the Beat and believes use as licensed does not infringe third‑party rights.")));

        doc.add(section(doc, styles, "10. Indemnification",
                para(styles.body, "Licensee will indemnify and hold Licensor harmless from claims arising from Licensee’s use beyond the scope of this Agreement.")));

        doc.add(section(doc, styles, "11. Limitation of Liability",
                para(styles.body, "Licensor shall not be liable for indirect, incidental, or consequential damages.")));

        doc.add(section(doc, styles, "12. Governing Law",
                para(styles.body, "Laws of the Netherlands; exclusive jurisdiction of the courts of Amsterdam.")));

        doc.add(section(doc, styles, "13. Entire Agreement",
                para(styles.body, "This is the entire agreement and supersedes prior understandings.")));

        doc.add(section(doc, styles, "14. Electronic Execution",
                para(styles.body, "Assent by click/checkout constitutes a binding signature. This document may be stored electronically.")));



        doc.close();

        // compute SHA-256 fingerprint on final bytes and append to doc metadata & footer
        byte[] pdfBytes = bos.toByteArray();
        String fingerprint = sha256Hex(pdfBytes);

        // Re‑open and stamp the fingerprint (optional but nice)
        PdfStamperUtil.appendFingerprint(filename, pdfBytes, fingerprint, agreementId);

        return filename;
    }

    public static String generateAlbumLicensePdf(User buyer, Album album) throws Exception {
        final String outDir = "licenses";
        Files.createDirectories(Paths.get(outDir));

        final String agreementId = "DL-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        final String filename = outDir + "/album-license-" + album.getId() + "-" + System.currentTimeMillis() + ".pdf";

        String payload = "agreementId=" + agreementId +
                "&type=ALBUM&albumId=" + album.getId() +
                "&buyer=" + safe(buyer.getEmail());

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        Document doc = PdfDocFactory.newDocument(bos, "Drilldex Album License", agreementId);

        PdfBranding.PageDecoration event = PdfBranding.pageDecoration(agreementId);
        PdfWriter writer = PdfBranding.newWriter(doc, bos, event);

        Styles styles = Styles.defaultStyles();
        doc.open();

        PdfBranding.addFrontMatter(doc, styles, "DRILLDEX MASTER ALBUM LICENSE AGREEMENT", agreementId);
        doc.add(Styles.vspace(6));
        doc.add(summaryTableForAlbum(buyer, album, styles));

        // Schedule A — per‑beat list
        doc.add(Styles.vspace(8));
        doc.add(new Paragraph("SCHEDULE A — Included Beats", styles.h2));
        com.lowagie.text.List list = new com.lowagie.text.List(com.lowagie.text.List.UNORDERED);
        list.setListSymbol("• ");
        for (Beat b : album.getBeats()) {
            // Beat title
            ListItem beatItem = new ListItem(new Phrase(b.getTitle(), styles.mono));

            if (b.getLicenses() != null && !b.getLicenses().isEmpty()) {
                for (BeatLicense bl : b.getLicenses()) {
                    if (!bl.isEnabled()) continue;

                    LicenseTerms lt = LicenseTermsConfig.getTermsFor(bl.getType());
                    String line = String.format(
                            "   └─ Tier: %s   |   Price: %s   |   Max Streams: %s",
                            bl.getType().name(),
                            money(bl.getPrice()),
                            unlimited(lt.getMaxStreams())
                    );
                    beatItem.add(new Phrase("\n" + line, styles.body));
                }
            } else {
                beatItem.add(new Phrase("\n   └─ No licenses configured", styles.body));
            }

            list.add(beatItem);
        }
        doc.add(list);

        // Standard terms (reuse)
        addStandardTerms(doc, styles);


        doc.close();

        byte[] pdfBytes = bos.toByteArray();
        String fingerprint = sha256Hex(pdfBytes);
        PdfStamperUtil.appendFingerprint(filename, pdfBytes, fingerprint, agreementId);
        return filename;
    }

    public static String generatePackLicensePdf(User buyer, Pack pack, LicenseTerms terms, LicenseType licenseType, BigDecimal price) throws Exception {
        final String outDir = "licenses";
        Files.createDirectories(Paths.get(outDir));

        final String agreementId = "DL-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        final String filename = outDir + "/pack-license-" + pack.getId() + "-" + System.currentTimeMillis() + ".pdf";

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        Document doc = PdfDocFactory.newDocument(bos, "Drilldex Pack License", agreementId);

        PdfBranding.PageDecoration event = PdfBranding.pageDecoration(agreementId);
        PdfWriter writer = PdfBranding.newWriter(doc, bos, event);

        Styles styles = Styles.defaultStyles();
        doc.open();

        // Header
        PdfBranding.addFrontMatter(doc, styles, "DRILLDEX LICENSE AGREEMENT", agreementId);

        // Summary table for pack & selected license
        doc.add(Styles.vspace(6));
        doc.add(summaryTableForPack(buyer, pack, licenseType, price, styles));

        // Rights / license sections (like per-beat)
        doc.add(Styles.vspace(10));
        section(doc, styles, "1. Grant of License",
                "Licensor grants Licensee a non-exclusive (unless marked Exclusive), worldwide, non-transferable license to use the musical works in this Pack under the terms below.");

        doc.add(section(doc, styles, "2. Rights Granted",
                bulletLines(styles.body,
                        "Commercial Use: " + yn(terms.isCommercialUse()),
                        "Max Streams: " + unlimited(terms.getMaxStreams()),
                        "Max Music Videos: " + unlimited(terms.getMaxMusicVideos()),
                        "Radio Broadcast: " + yn(terms.isRadioRights()),
                        "Live Performances: " + yn(terms.isLivePerformanceRights()),
                        "Includes Stems: " + yn(terms.isIncludesStems()),
                        "Exclusive Rights: " + yn(terms.isExclusive())
                )));

        // Numbered legal chapters
        doc.add(section(doc, styles, "3. Term", para(styles.body, "Perpetual unless otherwise agreed in writing.")));
        doc.add(section(doc, styles, "4. Territory", para(styles.body, "Worldwide.")));
        doc.add(section(doc, styles, "5. Restrictions",
                para(styles.body, "Licensee shall not resell, redistribute, or sublicense any Beat in isolation. No use in fingerprinting systems (e.g., Content ID) without explicit written consent.")));
        doc.add(section(doc, styles, "6. Ownership",
                para(styles.body, "Licensor retains all rights in the master recordings and underlying compositions. No ownership transfers under this Agreement.")));
        doc.add(section(doc, styles, "7. Credit",
                para(styles.body, "Where technically feasible: “Produced by Drilldex”.")));
        doc.add(section(doc, styles, "8. Payment",
                para(styles.body, "A one‑time licensing fee has been paid. No further royalties are due under this Agreement.")));
        doc.add(section(doc, styles, "9. Warranties",
                para(styles.body, "Licensor warrants it has the right to license the Beats and believes use as licensed does not infringe third‑party rights.")));
        doc.add(section(doc, styles, "10. Indemnification",
                para(styles.body, "Licensee will indemnify and hold Licensor harmless from claims arising from Licensee’s use beyond the scope of this Agreement.")));
        doc.add(section(doc, styles, "11. Limitation of Liability",
                para(styles.body, "Licensor shall not be liable for indirect, incidental, or consequential damages.")));
        doc.add(section(doc, styles, "12. Governing Law",
                para(styles.body, "Laws of the Netherlands; exclusive jurisdiction of the courts of Amsterdam.")));
        doc.add(section(doc, styles, "13. Entire Agreement",
                para(styles.body, "This is the entire agreement and supersedes prior understandings.")));
        doc.add(section(doc, styles, "14. Electronic Execution",
                para(styles.body, "Assent by click/checkout constitutes a binding signature. This document may be stored electronically.")));

        // Schedule A — Contents
        doc.add(Styles.vspace(8));
        doc.add(new Paragraph("SCHEDULE A — Contents", styles.h2));
        com.lowagie.text.List list = new com.lowagie.text.List(com.lowagie.text.List.UNORDERED);
        list.setListSymbol("• ");
        int i = 1;
        for (Beat b : pack.getBeats()) {
            list.add(new ListItem(new Phrase(i++ + ". " + b.getTitle(), styles.mono)));
        }
        doc.add(list);

        doc.close();

        // Compute SHA-256 fingerprint and append
        byte[] pdfBytes = bos.toByteArray();
        String fingerprint = sha256Hex(pdfBytes);
        PdfStamperUtil.appendFingerprint(filename, pdfBytes, fingerprint, agreementId);

        return filename;
    }

    /* ==============================
       Sections & Tables
       ============================== */

    private static Element summaryTableForBeat(User buyer, Beat beat, LicenseTerms t, Styles s) {
        PdfPTable tbl = table(2, 100f);
        addKV(tbl, "Agreement Date", nowHuman(), s);
        addKV(tbl, "Buyer", safe(buyer.getEmail()), s);
        addKV(tbl, "Beat", safe(beat.getTitle()), s);
        addKV(tbl, "License Tier", t.getType().name(), s);
        addKV(tbl, "Includes Stems", yn(t.isIncludesStems()), s);
        addKV(tbl, "Max Streams", unlimited(t.getMaxStreams()), s);
        addKV(tbl, "Max Music Videos", unlimited(t.getMaxMusicVideos()), s);
        addKV(tbl, "Exclusive", yn(t.isExclusive()), s);
        return tbl;
    }

    private static Element summaryTableForAlbum(User buyer, Album album, Styles s) {
        PdfPTable tbl = table(2, 100f);
        addKV(tbl, "Agreement Date", nowHuman(), s);
        addKV(tbl, "Buyer", safe(buyer.getEmail()), s);
        addKV(tbl, "Album", safe(album.getTitle()), s);
        addKV(tbl, "Tracks", String.valueOf(album.getBeats().size()), s);
        return tbl;
    }

    private static Element summaryTableForPack(User buyer, Pack pack, LicenseType licenseType, BigDecimal price, Styles s) {
        PdfPTable tbl = table(2, 100f);

        addKV(tbl, "Agreement Date", nowHuman(), s);
        addKV(tbl, "Buyer", safe(buyer.getEmail()), s);
        addKV(tbl, "Pack", safe(pack.getTitle()), s);
        addKV(tbl, "Items", String.valueOf(pack.getBeats().size()), s);

        // New rows for license info
        addKV(tbl, "License Type", licenseType != null ? licenseType.name() : "Standard", s);
        addKV(tbl, "Price", price != null ? "$" + price.toPlainString() : "N/A", s);

        return tbl;
    }

    private static void addStandardTermsForLicense(Document doc, Styles s, LicenseTerms terms) throws DocumentException {
        if (terms == null) return;

        doc.add(Styles.vspace(8));
        doc.add(new Paragraph("LICENSE TERMS", s.h2));

        // Table: 2 columns (key | value), 40% key, 60% value
        PdfPTable tbl = new PdfPTable(2);
        tbl.setWidthPercentage(100);
        tbl.setWidths(new float[]{40f, 60f});
        tbl.getDefaultCell().setBorder(Rectangle.NO_BORDER);
        tbl.getDefaultCell().setPadding(6f);
        tbl.setSplitLate(false);
        tbl.setSplitRows(true);

        addKV(tbl, "License Type", terms.getType().name(), s);
        addKV(tbl, "Max Streams", unlimited(terms.getMaxStreams()), s);
        addKV(tbl, "Max Music Videos", unlimited(terms.getMaxMusicVideos()), s);
        addKV(tbl, "Radio Rights", yn(terms.isRadioRights()), s);
        addKV(tbl, "Live Performance Rights", yn(terms.isLivePerformanceRights()), s);
        addKV(tbl, "Commercial Use", yn(terms.isCommercialUse()), s);
        addKV(tbl, "Includes Stems", yn(terms.isIncludesStems()), s);
        addKV(tbl, "Exclusive", yn(terms.isExclusive()), s);
        addKV(tbl, "Price", money(BigDecimal.valueOf(terms.getPrice())), s); // Use proper formatting

        doc.add(tbl);
    }

    private static Paragraph section(Document doc, Styles s, String heading, Element body) {
        Paragraph p = new Paragraph();
        p.add(new Paragraph(heading, s.h2));
        p.add(Styles.vspace(2));
        p.add(body);
        p.add(Styles.vspace(6));
        return p;
    }

    private static Element bulletLines(Font font, String... lines) {
        com.lowagie.text.List ul = new com.lowagie.text.List(com.lowagie.text.List.UNORDERED);
        ul.setListSymbol("• ");
        for (String l : lines) ul.add(new ListItem(new Phrase(l, font)));
        return ul;
    }

    private static Element para(Font font, String text) {
        return new Paragraph(text, font);
    }

    private static PdfPTable table(int cols, float widthPercent) {
        PdfPTable t = new PdfPTable(cols);
        t.setWidthPercentage(widthPercent);
        t.getDefaultCell().setBorder(Rectangle.NO_BORDER);
        t.getDefaultCell().setPadding(6f);
        t.setSplitLate(false);
        t.setSplitRows(true);
        return t;
    }

    private static void addKV(PdfPTable t, String k, String v, Styles s) {
        PdfPCell kCell = new PdfPCell(new Phrase(k, s.muted));
        kCell.setBorder(Rectangle.NO_BORDER);
        kCell.setPadding(6f);
        t.addCell(kCell);

        PdfPCell vCell = new PdfPCell(new Phrase(v, s.body));
        vCell.setBorder(Rectangle.NO_BORDER);
        vCell.setPadding(6f);
        t.addCell(vCell);
    }


    /* ==============================
       Common “legalese” block reuse
       ============================== */
    private static void addStandardTerms(Document doc, Styles s) throws DocumentException {
        doc.add(Styles.vspace(10));
        doc.add(section(doc, s, "1. Grant of Rights", para(s.body,
                "Licensor grants Licensee a non‑exclusive, worldwide, royalty‑free licence to reproduce, distribute, synchronise and publicly perform the Works within Licensee Content, subject to usage limitations herein.")));
        doc.add(section(doc, s, "2. Restrictions", para(s.body,
                "No resale/sublicense of the Works in isolation. No unlawful, hateful or defamatory use. No fingerprinting/Content‑ID without prior written consent.")));
        doc.add(section(doc, s, "3. Ownership", para(s.body,
                "All copyrights remain with Licensor. No ownership is transferred.")));
        doc.add(section(doc, s, "4. Term & Termination", para(s.body,
                "Perpetual unless terminated for material breach; upon termination, rights revert to Licensor.")));
        doc.add(section(doc, s, "5. Warranties; Liability", para(s.body,
                "Provided “as‑is”. Licensor disclaims indirect and consequential damages.")));
        doc.add(section(doc, s, "6. Governing Law & Venue", para(s.body,
                "Netherlands law; courts of Amsterdam have exclusive jurisdiction.")));
        doc.add(section(doc, s, "7. Electronic Execution", para(s.body,
                "Electronic assent constitutes a binding signature.")));
    }

    /* ==============================
       PDF Infrastructure & Styles
       ============================== */

    private static class PdfDocFactory {
        static Document newDocument(ByteArrayOutputStream bos, String title, String agreementId) {
            Document d = new Document(PageSize.A4, 56f, 56f, 72f, 56f); // nice margins
            d.addTitle(title);
            d.addSubject("Agreement " + agreementId);
            d.addAuthor("Drilldex");
            d.addCreator("Drilldex Licensing");
            return d;
        }
    }

    private static class PdfBranding {

        /** Create a PdfWriter and (optionally) attach a page event. */
        static PdfWriter newWriter(Document doc, OutputStream out, PdfPageEvent event) throws DocumentException {
            PdfWriter writer = PdfWriter.getInstance(doc, out);
            if (event != null) {
                writer.setPageEvent(event);
            }
            return writer;
        }

        static class PageDecoration extends PdfPageEventHelper {
            private final String agreementId;
            private final Font header = Styles.font(BaseFont.HELVETICA_BOLD, 10);
            private final Font footer = Styles.font(BaseFont.HELVETICA, 9);

            PageDecoration(String agreementId) {
                this.agreementId = agreementId;
            }

            @Override
            public void onEndPage(PdfWriter writer, Document document) {
                PdfContentByte cb = writer.getDirectContent();
                Rectangle r = document.getPageSize();

                // --- Header ---
                ColumnText.showTextAligned(
                        cb, Element.ALIGN_LEFT,
                        new Phrase("DRILLDEX", header),
                        document.left(), r.getTop() - 30, 0
                );

                ColumnText.showTextAligned(
                        cb, Element.ALIGN_RIGHT,
                        new Phrase("Agreement " + agreementId, header),
                        document.right(), r.getTop() - 30, 0
                );

                // --- Footer (timestamp + page number) ---
                String ts = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(LocalDateTime.now());

                ColumnText.showTextAligned(
                        cb, Element.ALIGN_LEFT,
                        new Phrase("Generated " + ts + " • drilldex.com", footer),
                        document.left(), r.getBottom() + 28, 0
                );

                ColumnText.showTextAligned(
                        cb, Element.ALIGN_RIGHT,
                        new Phrase("Page " + writer.getPageNumber(), footer),
                        document.right(), r.getBottom() + 28, 0
                );

                // --- Light watermark ---
                PdfGState gs = new PdfGState();
                gs.setFillOpacity(0.06f);
                cb.saveState();
                cb.setGState(gs);
                ColumnText.showTextAligned(
                        cb, Element.ALIGN_CENTER,
                        new Phrase(agreementId, Styles.font(BaseFont.HELVETICA_BOLD, 60)),
                        (r.getLeft() + r.getRight()) / 2,
                        (r.getTop() + r.getBottom()) / 2,
                        45
                );
                cb.restoreState();
            }
        }

        static PageDecoration pageDecoration(String agreementId) {
            return new PageDecoration(agreementId);
        }

        static void addFrontMatter(Document doc, Styles s, String title, String agreementId) throws DocumentException {
            doc.add(new Paragraph(title, s.h1));
            doc.add(Styles.vspace(4));
            doc.add(new Paragraph("Agreement ID: " + agreementId, s.muted));
        }
    }

    private static class Styles {
        final Font h1, h2, body, muted, mono, monoSmall;
        private Styles(Font h1, Font h2, Font body, Font muted, Font mono, Font monoSmall) {
            this.h1 = h1; this.h2 = h2; this.body = body; this.muted = muted; this.mono = mono; this.monoSmall = monoSmall;
        }

        static Styles defaultStyles() {
            return new Styles(
                    font(BaseFont.HELVETICA_BOLD, 18),
                    font(BaseFont.HELVETICA_BOLD, 13),
                    font(BaseFont.HELVETICA, 11.5f),
                    font(BaseFont.HELVETICA_OBLIQUE, 10),
                    font(BaseFont.COURIER, 10.5f),
                    font(BaseFont.COURIER, 9f)
            );
        }

        static Font font(String base, float size) {
            try {
                BaseFont bf = BaseFont.createFont(base, BaseFont.WINANSI, BaseFont.EMBEDDED);
                return new Font(bf, size);
            } catch (Exception e) {
                return new Font(Font.HELVETICA, size);
            }
        }

        static Paragraph vspace(float pts) {
            Paragraph p = new Paragraph(" ");
            p.setLeading(pts);
            return p;
        }
    }

    private static class PdfStamperUtil {
        static void appendFingerprint(String filename, byte[] pdfBytes, String fingerprint, String agreementId) throws Exception {
            // write initial file
            try (FileOutputStream fos = new FileOutputStream(filename)) {
                fos.write(pdfBytes);
            }

            // stamp the fingerprint on the last page footer line (simple approach)
            PdfReader reader = new PdfReader(filename);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PdfStamper stamper = new PdfStamper(reader, out);

            PdfContentByte cb = stamper.getOverContent(reader.getNumberOfPages());
            ColumnText.showTextAligned(cb, Element.ALIGN_LEFT,
                    new Phrase("Fingerprint (SHA-256): " + fingerprint, Styles.font(BaseFont.COURIER, 8.5f)),
                    56f, 38f, 0);

            stamper.close();
            reader.close();

            try (FileOutputStream fos = new FileOutputStream(filename)) {
                fos.write(out.toByteArray());
            }
        }
    }

    /* ==============================
       Utils
       ============================== */

    private static String nowHuman() {
        return DateTimeFormatter.ofPattern("MMMM d, yyyy - HH:mm", Locale.ENGLISH).format(LocalDateTime.now());
    }

    private static String yn(boolean b) { return b ? "Yes" : "No"; }
    private static String unlimited(int v) { return v == Integer.MAX_VALUE ? "Unlimited" : String.valueOf(v); }
    private static String safe(String s) { return s == null ? "" : s; }

    private static String money(double amount, Locale locale) {
        NumberFormat nf = NumberFormat.getCurrencyInstance(locale);
        return nf.format(amount);
    }

    private static String sha256Hex(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] d = md.digest(data);
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static void section(Document doc, Styles s, String heading, String bodyText)
            throws DocumentException {
        section(doc, s, heading, new Paragraph(bodyText, s.body));
    }

    private static String money(BigDecimal v) {
        java.text.NumberFormat nf = java.text.NumberFormat.getCurrencyInstance(java.util.Locale.US);
        return nf.format(v);
    }


}
