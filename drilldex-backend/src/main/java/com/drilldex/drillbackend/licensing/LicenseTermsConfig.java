package com.drilldex.drillbackend.licensing;

import com.drilldex.drillbackend.album.Album;
import com.drilldex.drillbackend.beat.LicenseType;
import com.drilldex.drillbackend.user.User;
import com.lowagie.text.Document;
import com.lowagie.text.Font;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfWriter;

import java.io.FileOutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.Map;

public class LicenseTermsConfig {

    private static final Map<LicenseType, LicenseTerms> terms = new EnumMap<>(LicenseType.class);

    static {
        // Adjust numbers/flags to your business rules. These are sane defaults.
        terms.put(LicenseType.MP3, new LicenseTerms(
                LicenseType.MP3,
                50_000,          // max streams
                0,               // max music videos
                false,           // stems included
                false,           // YouTube monetization
                true,            // live shows allowed
                false,           // radio broadcast
                false,           // exclusive
                20.0             // suggested minimum price
        ));

        terms.put(LicenseType.WAV, new LicenseTerms(
                LicenseType.WAV,
                250_000,
                1,
                false,
                true,
                true,
                false,
                false,
                50.0
        ));

        terms.put(LicenseType.PREMIUM, new LicenseTerms(
                LicenseType.PREMIUM,
                1_000_000,
                3,
                true,            // stems included
                true,
                true,
                true,
                false,
                120.0
        ));

        terms.put(LicenseType.EXCLUSIVE, new LicenseTerms(
                LicenseType.EXCLUSIVE,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                true,
                true,
                true,
                true,
                true,            // exclusive
                500.0
        ));
    }

    public static LicenseTerms getTermsFor(LicenseType type) {
        return terms.get(type);
    }

    public static String generateAlbumLicensePdf(User buyer, Album album) throws Exception {
        String filename = "licenses/album-license-" + album.getId() + "-" + System.currentTimeMillis() + ".pdf";

        Document document = new Document();
        PdfWriter.getInstance(document, new FileOutputStream(filename));
        document.open();

        Font titleFont = new Font(Font.HELVETICA, 18, Font.BOLD);
        Font sectionFont = new Font(Font.HELVETICA, 12, Font.NORMAL);

        document.add(new Paragraph("Drilldex Album License Agreement", titleFont));
        document.add(new Paragraph(" "));

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MMMM d, yyyy - HH:mm");
        String formattedDate = LocalDateTime.now().format(formatter);

        document.add(new Paragraph("Buyer: " + buyer.getEmail(), sectionFont));
        document.add(new Paragraph("Album: " + album.getTitle(), sectionFont));
        document.add(new Paragraph("Purchase Date: " + formattedDate, sectionFont));
        document.add(new Paragraph(" "));

        document.add(new Paragraph("This license grants the buyer the rights to use all included beats under their respective terms.", sectionFont));
        document.add(new Paragraph("Each beat included in this album is covered by the license tier chosen at the time of purchase.", sectionFont));
        document.add(new Paragraph("For any questions or additional licensing needs, please contact the Drilldex team.", sectionFont));

        document.add(new Paragraph(" "));
        document.add(new Paragraph("Thank you for purchasing from Drilldex!", sectionFont));

        document.close();
        return filename;
    }
}