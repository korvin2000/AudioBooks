package com.fanok.audiobooks.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.fanok.audiobooks.pojo.GenrePOJO;

import org.junit.Assume;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/** assets/people/people.bin holds every built-in list the registry opens, each linking to its own site. */
public class PeopleIndexTest {

    @Test
    public void everyBuiltInDrawerListIsInTheAsset() throws IOException {
        PeopleIndex index = PeopleIndex.of(Files.readAllBytes(Paths.get("src/main/assets/people/people.bin")));
        int lists = 0;
        for (Provider p : Provider.values()) {
            for (String url : new String[]{p.authors, p.performers}) {
                if (!PeopleIndex.isBuiltIn(url)) {
                    continue;
                }
                assertEquals(p + " drawer url", p.server + (url.endsWith(PeopleIndex.READERS)
                        ? PeopleIndex.READERS : PeopleIndex.AUTHORS), url);
                boolean readers = url.endsWith(PeopleIndex.READERS);
                assertTrue(p + " " + url, index.has(p.name(), readers));
                List<GenrePOJO> people = index.list(p.name(), readers);
                assertFalse(p + " " + url, people.isEmpty());
                String top = people.get(0).getUrl(), first = p.parser.pageUrl(top, 1);
                assertTrue(p + " opens " + first, first.equals(top) || first.startsWith(top + "?")
                        || top.endsWith("=")); // poleknig's ?p=1, knigoblud's search &page=1
                for (GenrePOJO person : people) {
                    assertFalse(person.getUrl(), person.getName().isEmpty());
                    assertEquals(person.getName() + " " + person.getUrl(), p, Provider.ofUrl(person.getUrl()));
                }
                lists++;
            }
        }
        assertEquals("ten sites, poleknig and slushat-knigi without readers", 18, lists);
        assertFalse(index.has(Provider.OTRUB.name(), false));
    }

    /** Each list decodes to its tools/people tsv exactly: name, url, books (0 = unknown), in order. */
    @Test
    public void everyListIsItsTsv() throws IOException {
        Path lists = Paths.get("../tools/people/lists");
        Assume.assumeTrue("the crawler's lists are at hand", Files.isDirectory(lists));
        PeopleIndex index = PeopleIndex.of(Files.readAllBytes(Paths.get("src/main/assets/people/people.bin")));
        int checked = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(lists, "*.tsv")) {
            for (Path tsv : files) {
                String[] name = tsv.getFileName().toString().split("\\.");
                boolean readers = name[1].equals("readers");
                if (!index.has(name[0], readers)) {
                    continue;
                }
                List<String> want = Files.readAllLines(tsv, StandardCharsets.UTF_8);
                List<GenrePOJO> got = index.list(name[0], readers);
                assertEquals(tsv.toString(), want.size(), got.size());
                for (int i = 0; i < want.size(); i++) {
                    GenrePOJO p = got.get(i);
                    assertEquals(tsv + ":" + (i + 1), want.get(i), p.getName() + "\t" + p.getUrl() + "\t" + p.getReting());
                }
                checked++;
            }
        }
        assertEquals("the 18 drawer lists and fantlab's narrators", 19, checked);
    }
}
