package kbasesearchengine.test.search;

import static kbasesearchengine.test.common.TestCommon.set;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.Appender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import org.apache.commons.io.FileUtils;
import org.apache.http.HttpHost;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;


import com.fasterxml.jackson.core.JsonParser;
import com.google.common.collect.ImmutableMap;

import junit.framework.Assert;
import kbasesearchengine.common.GUID;
import kbasesearchengine.common.ObjectJsonPath;
import kbasesearchengine.events.exceptions.ErrorType;
import kbasesearchengine.events.exceptions.FatalIndexingException;
import kbasesearchengine.events.exceptions.IndexingException;
import kbasesearchengine.events.handler.SourceData;
import kbasesearchengine.parse.IdMapper;
import kbasesearchengine.parse.KeywordParser;
import kbasesearchengine.parse.ObjectParseException;
import kbasesearchengine.parse.ObjectParser;
import kbasesearchengine.parse.ParsedObject;
import kbasesearchengine.parse.SimpleIdConsumer;
import kbasesearchengine.parse.SimpleSubObjectConsumer;
import kbasesearchengine.parse.SubObjectConsumer;
import kbasesearchengine.parse.KeywordParser.ObjectLookupProvider;
import kbasesearchengine.search.AccessFilter;
import kbasesearchengine.search.ElasticIndexingStorage;
import kbasesearchengine.search.MatchFilter;
import kbasesearchengine.search.MatchFilter.Builder;
import kbasesearchengine.search.MatchValue;
import kbasesearchengine.search.ObjectData;
import kbasesearchengine.search.PostProcessing;
import kbasesearchengine.search.SortingRule;
import kbasesearchengine.search.FoundHits;
import kbasesearchengine.search.IndexingConflictException;
import kbasesearchengine.system.IndexingRules;
import kbasesearchengine.system.ObjectTypeParsingRules;
import kbasesearchengine.system.ObjectTypeParsingRulesFileParser;
import kbasesearchengine.system.SearchObjectType;
import kbasesearchengine.system.StorageObjectType;
import kbasesearchengine.test.common.TestCommon;
import kbasesearchengine.test.controllers.elasticsearch.ElasticSearchController;
import kbasesearchengine.test.parse.SubObjectExtractorTest;
import org.junit.rules.ExpectedException;
import org.mockito.ArgumentMatcher;
import org.slf4j.LoggerFactory;
import us.kbase.common.service.UObject;

public class ElasticIndexingStorageTest {

    private static ElasticIndexingStorage indexStorage;
    private static File tempDir = null;
    private static ObjectLookupProvider objLookup;
    private static ElasticSearchController es;

    //TODO TEST should delete indexes between every test to keep them independent

    public ElasticIndexingStorageTest() {

    }

    @BeforeClass
    public static void prepare() throws Exception {
        TestCommon.stfuLoggers();
        final Path tdir = Paths.get(TestCommon.getTempDir());
        tempDir = tdir.resolve("ElasticIndexingStorageTest").toFile();
        FileUtils.deleteQuietly(tempDir);
        es = new ElasticSearchController(TestCommon.getElasticSearchExe(), tdir);
        String indexNamePrefix = "test_" + System.currentTimeMillis() + ".";
        indexStorage = new ElasticIndexingStorage(
                new HttpHost("localhost", es.getServerPort()), tempDir);
        indexStorage.setIndexNamePrefix(indexNamePrefix);
        tempDir.mkdirs();
        objLookup = new ObjectLookupProvider() {

            @Override
            public Set<GUID> resolveRefs(List<GUID> callerRefPath, Set<GUID> refs) {
                for (GUID pguid : refs) {
                    try {
                        boolean indexed = indexStorage.checkParentGuidsExist(new LinkedHashSet<>(
                                Arrays.asList(pguid))).get(pguid);
                        if (!indexed) {
                            indexObject("Assembly", 0, "assembly01", pguid, "MyAssembly.1");
                            indexObject("AssemblyContig", 0, "assembly01", pguid, "MyAssembly.1");
                            Assert.assertTrue(indexStorage.checkParentGuidsExist(new LinkedHashSet<>(
                                    Arrays.asList(pguid))).get(pguid));
                        }
                    } catch (Exception ex) {
                        throw new IllegalStateException(ex);
                    }
                }
                return refs;
            }

            @Override
            public Map<GUID, ObjectData> lookupObjectsByGuid(Set<GUID> guids)
                    throws FatalIndexingException {
                List<ObjectData> objList;
                try {
                    objList = indexStorage.getObjectsByIds(guids);
                } catch (IOException e) {
                    throw new FatalIndexingException(ErrorType.OTHER, e.getMessage(), e);
                }
                return objList.stream().collect(
                        Collectors.toMap(od -> od.getGUID(), Function.identity()));
            }

            @Override
            public ObjectTypeParsingRules getTypeDescriptor(SearchObjectType type) {
                try {
                    final File rulesFile = new File("resources/types/" + type.getType() + ".yaml");
                    return ObjectTypeParsingRulesFileParser.fromFile(rulesFile)
                            .get(type.getVersion() - 1);
                } catch (Exception ex) {
                    throw new IllegalStateException(ex);
                }
            }

            @Override
            public Map<GUID, SearchObjectType> getTypesForGuids(Set<GUID> guids)
                    throws FatalIndexingException {
                PostProcessing pp = new PostProcessing();
                pp.objectData = false;
                pp.objectKeys = false;
                try {
                    return indexStorage.getObjectsByIds(guids, pp).stream().collect(
                            Collectors.toMap(od -> od.getGUID(), od -> od.getType()));
                } catch (IOException e) {
                    throw new FatalIndexingException(ErrorType.OTHER, e.getMessage(), e);
                }
            }
        };
    }

    @After
    public void cleanup() throws Exception {
        indexStorage.dropData();
    }

    @AfterClass
    public static void teardown() throws Exception {
        if (es != null) {
            es.destroy(TestCommon.getDeleteTempFiles());
        }
        if (tempDir != null && tempDir.exists() && TestCommon.getDeleteTempFiles()) {
            FileUtils.deleteQuietly(tempDir);
        }
    }

    private static MatchFilter ft(String fullText) {
        return MatchFilter.getBuilder().withNullableFullTextInAll(fullText).build();
    }

    private static void indexObject(
            final GUID id,
            final ObjectTypeParsingRules rule,
            final String json,
            final String objectName,
            final Instant timestamp,
            final String parentJsonValue,
            final boolean isPublic)
            throws IOException, ObjectParseException, IndexingException, InterruptedException,
                IndexingConflictException {
        ParsedObject obj = KeywordParser.extractKeywords(id, rule.getGlobalObjectType(), json,
                parentJsonValue, rule.getIndexingRules(), objLookup, null);
        final SourceData data = SourceData.getBuilder(new UObject(json), objectName, "creator")
                .build();
        indexStorage.indexObject(rule, data, timestamp, parentJsonValue, id, obj, isPublic);
    }
    
    private static void indexObject(
            final String type,
            final int version,
            final String jsonResource,
            final GUID ref,
            final String objName)
            throws Exception {
        final File file = new File("resources/types/" + type + ".yaml");
        ObjectTypeParsingRules parsingRules = ObjectTypeParsingRulesFileParser.fromFile(file).get(version);
        Map<ObjectJsonPath, String> pathToJson = new LinkedHashMap<>();
        SubObjectConsumer subObjConsumer = new SimpleSubObjectConsumer(pathToJson);
        String parentJson = null;
        try (JsonParser jts = SubObjectExtractorTest.getParsedJsonResource(jsonResource)) {
            parentJson = ObjectParser.extractParentFragment(parsingRules, jts);
        }
        try (JsonParser jts = SubObjectExtractorTest.getParsedJsonResource(jsonResource)) {
            ObjectParser.extractSubObjects(parsingRules, subObjConsumer, jts);
        }
        for (ObjectJsonPath path : pathToJson.keySet()) {
            String subJson = pathToJson.get(path);
            SimpleIdConsumer idConsumer = new SimpleIdConsumer();
            if (parsingRules.getSubObjectIDPath().isPresent()) {
                try (JsonParser subJts = UObject.getMapper().getFactory().createParser(subJson)) {
                    IdMapper.mapKeys(parsingRules.getSubObjectIDPath().get(), subJts, idConsumer);
                }
            }
            GUID id = ObjectParser.prepareGUID(parsingRules, ref, path, idConsumer);
            indexObject(id, parsingRules, subJson, objName, Instant.now(), parentJson, false);
        }

    }

    private static ObjectData getIndexedObject(GUID guid) throws Exception {
        return indexStorage.getObjectsByIds(new LinkedHashSet<>(Arrays.asList(guid))).get(0);
    }



    /** Verifies that the is_last flag in the index records is set for the record with the latest
     * version across all indexes for the same type. For example, if genome_1 and genome_2 are the
     * two indexes that exist for the type genome, where genome_1 contains records for some genome
     * type version set x and genome_2 contains records for some genome type version set y, and the
     * latest genome object version resides in, say genome_1, then only this record's islast field
     * must be set among all records contained in genome_1 and genome_2. i.e. all records in genome_2
     * must have their islast field set to false in this case.
     *
     */
    @Test
    public void testLastVersionForParentObjects() throws Exception {
        indexObject("Genome", 1, "genome01", new GUID("WS:1/1/4"), "MyGenome.1");
        indexObject("Genome", 1, "genome01", new GUID("WS:1/1/3"), "MyGenome.1");

        indexObject("Genome", 0, "genome01", new GUID("WS:1/1/2"), "MyGenome.1");
        indexObject("Genome", 0, "genome01", new GUID("WS:1/1/1"), "MyGenome.1");

        MatchFilter filter = MatchFilter.getBuilder().build();
        AccessFilter accessFilter = AccessFilter.create().withAllHistory(false).withAdmin(true);

        FoundHits hits = indexStorage.searchObjects(Arrays.asList("Genome"),
                filter, null, accessFilter, null, null);
        Assert.assertEquals("expected 1 guid", 1, hits.guids.size());
        Assert.assertEquals("expected WS:1/1/4",
                new GUID("WS:1/1/4"), hits.guids.iterator().next());
    }

    @Test
    public void testLastVersionForSubObjects() throws Exception {
        indexObject("GenomeFeature", 1, "genome01",
                new GUID("WS:1/1/4"), "MyGenome.1");
        indexObject("GenomeFeature", 1, "genome01",
                new GUID("WS:1/1/3"), "MyGenome.1");
        indexObject("GenomeFeature", 0, "genome01",
                new GUID("WS:1/1/2"), "MyGenome.1");
        indexObject("GenomeFeature", 0, "genome01",
                new GUID("WS:1/1/1"), "MyGenome.1");

        MatchFilter filter = MatchFilter.getBuilder().build();
        AccessFilter accessFilter = AccessFilter.create().withAllHistory(false).withAdmin(true);

        FoundHits hits = indexStorage.searchObjects(Arrays.asList("GenomeFeature"),
                                       filter, null, accessFilter, null, null);
        // genome01 has 3 features
        Assert.assertEquals("expected 3 guid", 3, hits.guids.size());
        Assert.assertTrue("expected WS:1/1/4:Feature/NewGenome.CDS",
                hits.guids.iterator().next().toString().startsWith("WS:1/1/4:Feature/NewGenome.CDS"));
    }



    @SuppressWarnings("unchecked")
    @Test
    public void testFeatures() throws Exception {
        indexObject("GenomeFeature", 0, "genome01",
                new GUID("WS:1/1/1"), "MyGenome.1");
        Map<String, Integer> typeToCount = indexStorage.searchTypes(ft("Rfah"),
                AccessFilter.create().withAdmin(true));
        Assert.assertEquals(1, typeToCount.size());
        List<String> type = ImmutableList.of(typeToCount.keySet().iterator().next());
        Assert.assertEquals(1, (int)typeToCount.get(type.get(0)));
        GUID expectedGUID = new GUID("WS:1/1/1:feature/NewGenome.CDS.6210");
        // Admin mode
        Set<GUID> ids = indexStorage.searchIds(type, ft("RfaH"), null,
                AccessFilter.create().withAdmin(true));
        Assert.assertEquals(1, ids.size());
        GUID id = ids.iterator().next();
        Assert.assertEquals(expectedGUID, id);
        // Wrong groups
        ids = indexStorage.searchIds(type, ft("RfaH"), null,
                AccessFilter.create().withAccessGroups(2,3));
        Assert.assertEquals(0, ids.size());
        // Right groups
        Set<Integer> accessGroupIds = new LinkedHashSet<>(Arrays.asList(1, 2, 3));
        ids = indexStorage.searchIds(type, ft("RfaH"), null,
                AccessFilter.create().withAccessGroups(accessGroupIds));
        Assert.assertEquals(1, ids.size());
        id = ids.iterator().next();
        Assert.assertEquals(expectedGUID, id);
        // Check object loading by IDs
        List<ObjectData> objList = indexStorage.getObjectsByIds(
                new HashSet<>(Arrays.asList(id)));
        Assert.assertEquals(1, objList.size());
        ObjectData featureIndex = objList.get(0);
        //System.out.println("GenomeFeature index: " + featureIndex);
        Map<String, Object> obj = (Map<String, Object>)featureIndex.getData().get();
        Assert.assertTrue(obj.containsKey("id"));
        Assert.assertTrue(obj.containsKey("location"));
        Assert.assertTrue(obj.containsKey("function"));
        Assert.assertTrue(obj.containsKey("type"));
        Assert.assertEquals("NC_000913", featureIndex.getKeyProperties().get("contig_id"));
        String contigGuidText = featureIndex.getKeyProperties().get("contig_guid");
        Assert.assertNotNull("missing contig_guid", contigGuidText);
        ObjectData contigIndex = getIndexedObject(new GUID(contigGuidText));
        //System.out.println("AssemblyContig index: " + contigIndex);
        Assert.assertEquals("NC_000913", "" + contigIndex.getKeyProperties().get("contig_id"));
        // Search by keyword
        ids = indexStorage.searchIds(type, MatchFilter.getBuilder().withLookupInKey(
                "ontology_terms", "SSO:000008186").build(), null,
                AccessFilter.create().withAccessGroups(accessGroupIds));
        Assert.assertEquals(1, ids.size());
        id = ids.iterator().next();
        Assert.assertEquals(expectedGUID, id);
    }

    @Test
    public void testGenome() throws Exception {
        System.out.println("*** start testGenome***");
        indexObject("Genome", 0, "genome01", new GUID("WS:1/1/1"), "MyGenome.1");
        Set<GUID> guids = indexStorage.searchIds(ImmutableList.of("Genome"),
                MatchFilter.getBuilder().withLookupInKey(
                        "feature_count", new MatchValue(1, null)).build(),
                null, AccessFilter.create().withAdmin(true));
        Assert.assertEquals(1, guids.size());
        ObjectData genomeIndex = indexStorage.getObjectsByIds(guids).get(0);
        //System.out.println("Genome index: " + genomeIndex);
        Assert.assertTrue(genomeIndex.getKeyProperties().containsKey("feature_count"));
        Assert.assertEquals("KBase", "" + genomeIndex.getKeyProperties().get("source"));
        Assert.assertEquals("NewGenome", "" +
                genomeIndex.getKeyProperties().get("source_id"));
        Assert.assertEquals("Shewanella", "" +
                genomeIndex.getKeyProperties().get("scientific_name"));
        Assert.assertEquals("Shewanella", "" +
                genomeIndex.getKeyProperties().get("scientific_name_keyword"));
        Assert.assertEquals("3", "" + genomeIndex.getKeyProperties().get("feature_count"));
        Assert.assertEquals("1", "" + genomeIndex.getKeyProperties().get("cds_count"));
        Assert.assertEquals("1", "" + genomeIndex.getKeyProperties().get("contig_count"));
        String assemblyGuidText = genomeIndex.getKeyProperties().get("assembly_guid");
        Assert.assertNotNull(assemblyGuidText);
        ObjectData assemblyIndex = getIndexedObject(new GUID(assemblyGuidText));
        //System.out.println("Assembly index: " + genomeIndex);
        Assert.assertEquals("1", "" + assemblyIndex.getKeyProperties().get("contig_count"));
        System.out.println("*** end testGenome***");
    }

    @Test
    public void testPangenome() throws Exception {
        System.out.println("*** start testPangenome***");
        indexObject("Pangenome", 0, "pangenome01",
                new GUID("WS:1/1/1"), "Pangenome.1");
        Set<GUID> guids = indexStorage.searchIds(ImmutableList.of("Pangenome"),
                        ft("Pangenome"), null, AccessFilter.create().withAdmin(true));
        Assert.assertEquals(1, guids.size());
        ObjectData pangenome = indexStorage.getObjectsByIds(guids).get(0);
        System.out.println("Genome index: " + pangenome.getKeyProperties());
        Assert.assertEquals("kmer", pangenome.getKeyProperties().get("type"));
        Assert.assertEquals("3", "" + pangenome.getKeyProperties().get("orthologs"));
        Assert.assertEquals("2", "" + pangenome.getKeyProperties().get("genomes"));
        Assert.assertNotNull(pangenome.getKeyProperties().get("name"));
        System.out.println("*** end testPangenome***");
    }

    @Test
    public void testPangenomeOrthologFamily() throws Exception {
        System.out.println("*** start testPangenomeOrthologFamily***");
        indexObject("PangenomeOrthologFamily", 0, "pangenome01",
                new GUID("WS:1/1/1"), "Pangenome.1");
        Set<GUID> guids = indexStorage.searchIds(ImmutableList.of("PangenomeOrthologFamily"),
                ft("kb|g.220339.CDS.2352"),
                null, AccessFilter.create().withAdmin(true));
        Assert.assertEquals(1, guids.size());
        ObjectData pangenome = indexStorage.getObjectsByIds(guids).get(0);
        System.out.println("Genome index: " + pangenome.getKeyProperties());
        Assert.assertEquals("kb|g.220339.CDS.2352", pangenome.getKeyProperties().get("id"));
        Assert.assertEquals("hypothetical protein", pangenome.getKeyProperties().get("function"));
        Assert.assertEquals("kb|g.220339.CDS.2352", pangenome.getKeyProperties().get("ortholog_genes"));
        System.out.println("*** end testPangenomeOrthologFamily***");
    }

    @Test
    public void testGeneTree() throws Exception {
        System.out.println("*** start testGeneTree***");
        indexObject("Tree", 0, "genetree01",
                new GUID("WS:1/1/1"), "GeneTree.1");
        Set<GUID> guids = indexStorage.searchIds(ImmutableList.of("Tree"),
                MatchFilter.getBuilder().withLookupInKey("type", "GeneTree").build(),
                null, AccessFilter.create().withAdmin(true));
        Assert.assertEquals(1, guids.size());
        ObjectData index = indexStorage.getObjectsByIds(guids).get(0);
        System.out.println("Genome index: " + index.getKeyProperties());
        Assert.assertTrue(index.getKeyProperties().containsKey("labels"));
        Assert.assertEquals("GeneTree", "" + index.getKeyProperties().get("type"));
        System.out.println("*** end testGeneTree***");
    }

    @Test
    public void testSpeciesTree() throws Exception {
        System.out.println("*** start testSpeciesTree***");
        indexObject("Tree", 0, "speciestree01",
                new GUID("WS:1/1/1"), "SpeciesTree.1");
        Set<GUID> guids = indexStorage.searchIds(ImmutableList.of("Tree"),
                MatchFilter.getBuilder().withLookupInKey("type", "SpeciesTree").build(),
                null, AccessFilter.create().withAdmin(true));
        Assert.assertEquals(1, guids.size());
        ObjectData index = indexStorage.getObjectsByIds(guids).get(0);
        System.out.println("Genome index: " + index.getKeyProperties());
        Assert.assertTrue(index.getKeyProperties().containsKey("labels"));
        Assert.assertEquals("SpeciesTree", "" + index.getKeyProperties().get("type"));
        System.out.println("*** end testSpeciesTree***");
    }

    @Test
    public void testRNASeqSampleSet() throws Exception {
        System.out.println("*** start testRNASeqSampleSet***");
        indexObject("RNASeqSampleSet", 0, "rnaseqsampleset01",
                new GUID("WS:1/1/1"), "RNASeqSampleSet.1");
        Set<GUID> guids = indexStorage.searchIds(ImmutableList.of("RNASeqSampleSet"),
                MatchFilter.getBuilder().withLookupInKey("source", "NCBI SRP003951").build(),
                null, AccessFilter.create().withAdmin(true));
        Assert.assertEquals(1, guids.size());
        ObjectData index = indexStorage.getObjectsByIds(guids).get(0);
        System.out.println("Index: " + index.getKeyProperties());
        String desc = "Arabidopsis thaliana wild type and hy5-215 seedlings grown under white light.";
        Assert.assertEquals(desc, "" + index.getKeyProperties().get("sampleset_desc"));
        Assert.assertEquals("NCBI SRP003951", "" + index.getKeyProperties().get("source"));
        Assert.assertEquals("4", "" + index.getKeyProperties().get("num_samples"));
        Assert.assertEquals("3", "" + index.getKeyProperties().get("num_replicates"));
        System.out.println("*** end testRNASeqSampleSet***");
    }

    @Test
    public void testGenomeV2() throws Exception {
        System.out.println("*** start testGenomeV2***");
        indexObject("Genome", 1, "genome02",
                new GUID("WS:1/1/1"), "MyGenome.2");
        Set<GUID> guids = indexStorage.searchIds(ImmutableList.of("Genome"),
                MatchFilter.getBuilder().withLookupInKey(
                        "feature_count", new MatchValue(1, null)).build(),
                null, AccessFilter.create().withAdmin(true));
        Assert.assertEquals(1, guids.size());
        ObjectData genomeIndex = indexStorage.getObjectsByIds(guids).get(0);
        //System.out.println("Genome index: " + genomeIndex);
        Assert.assertTrue(genomeIndex.getKeyProperties().containsKey("feature_count"));
        Assert.assertEquals("KBase", "" + genomeIndex.getKeyProperties().get("source"));
        Assert.assertEquals("NewGenome", "" + genomeIndex.getKeyProperties().get("source_id"));
        Assert.assertEquals("Shewanella", "" +
                genomeIndex.getKeyProperties().get("scientific_name"));
        Assert.assertEquals("Shewanella", "" +
                genomeIndex.getKeyProperties().get("scientific_name_keyword"));
        Assert.assertEquals("3", "" + genomeIndex.getKeyProperties().get("feature_count"));
        Assert.assertEquals("1", "" + genomeIndex.getKeyProperties().get("contig_count"));
        String assemblyGuidText = genomeIndex.getKeyProperties().get("assembly_guid");
        Assert.assertNotNull(assemblyGuidText);
        ObjectData assemblyIndex = getIndexedObject(new GUID(assemblyGuidText));
        //System.out.println("Assembly index: " + genomeIndex);
        Assert.assertEquals("1", "" + assemblyIndex.getKeyProperties().get("contig_count"));
        System.out.println("*** end testGenomeV2***");
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testFeaturesV2() throws Exception {
        indexObject("GenomeFeature", 1, "genome02",
                new GUID("WS:1/1/1"), "MyGenome.2");
        Map<String, Integer> typeToCount = indexStorage.searchTypes(ft("b0001"),
                AccessFilter.create().withAdmin(true));
        Assert.assertEquals(1, typeToCount.size());
        List<String> type = ImmutableList.of(typeToCount.keySet().iterator().next());
        Assert.assertEquals(1, (int)typeToCount.get(type.get(0)));
        GUID expectedGUID = new GUID("WS:1/1/1:Feature/b0001");
        // Admin mode
        Set<GUID> ids = indexStorage.searchIds(type, ft("b0001"), null,
                AccessFilter.create().withAdmin(true));
        Assert.assertEquals(1, ids.size());
        GUID id = ids.iterator().next();
        Assert.assertEquals(expectedGUID, id);
        Set<Integer> accessGroupIds = new LinkedHashSet<>(Arrays.asList(1, 2, 3));
        List<ObjectData> objList = indexStorage.getObjectsByIds(
                new HashSet<>(Arrays.asList(id)));
        ObjectData featureIndex = objList.get(0);
        System.out.println("GenomeFeature index: " + featureIndex.getKeyProperties());
        Map<String, Object> obj = (Map<String, Object>)featureIndex.getData().get();
        Assert.assertTrue(obj.containsKey("id"));
        Assert.assertTrue(obj.containsKey("location"));
        Assert.assertTrue(obj.containsKey("functions"));
        Assert.assertTrue(obj.containsKey("aliases"));
        Assert.assertTrue(obj.containsKey("type"));
        Assert.assertEquals("NC_000913", featureIndex.getKeyProperties().get("contig_id"));
        String contigGuidText = featureIndex.getKeyProperties().get("contig_guid");
        Assert.assertNotNull("missing contig_guid", contigGuidText);
        ObjectData contigIndex = getIndexedObject(new GUID(contigGuidText));
        Assert.assertEquals("NC_000913", "" + contigIndex.getKeyProperties().get("contig_id"));
        // Search by keyword
        ids = indexStorage.searchIds(type, MatchFilter.getBuilder().withLookupInKey(
                "aliases", "b0001").build(), null,
                AccessFilter.create().withAccessGroups(accessGroupIds));
        Assert.assertEquals(1, ids.size());
        id = ids.iterator().next();
        Assert.assertEquals(expectedGUID, id);
    }

    @Test
    public void testNonCodingFeatureV2() throws Exception {
        indexObject("GenomeNonCodingFeature", 0, "genome02",
                new GUID("WS:1/1/1"), "MyGenome.2");
        Map<String, Integer> typeToCount = indexStorage.searchTypes(ft("repeat_region_1"),
                AccessFilter.create().withAdmin(true));
        Assert.assertEquals(1, typeToCount.size());
        List<String> type = ImmutableList.of(typeToCount.keySet().iterator().next());
        Assert.assertEquals(1, (int)typeToCount.get(type.get(0)));
        GUID expectedGUID = new GUID("WS:1/1/1:NonCodingFeature/repeat_region_1");
        // Admin mode
        Set<GUID> ids = indexStorage.searchIds(type, ft("repeat_region_1"), null,
                AccessFilter.create().withAdmin(true));
        Assert.assertEquals(1, ids.size());
        GUID id = ids.iterator().next();
        Assert.assertEquals(expectedGUID, id);
        List<ObjectData> objList = indexStorage.getObjectsByIds(
                new HashSet<>(Arrays.asList(id)));
        ObjectData featureIndex = objList.get(0);
        System.out.println("GenomeFeature index: " + featureIndex.getKeyProperties());
        @SuppressWarnings("unchecked")
        Map<String, Object> obj = (Map<String, Object>)featureIndex.getData().get();
        Assert.assertEquals("repeat_region_1", obj.get("id"));
        Assert.assertEquals("repeat_region", obj.get("type"));
        Assert.assertTrue(obj.containsKey("location"));
        Assert.assertTrue(obj.containsKey("note"));
        Assert.assertEquals("NC_000913", featureIndex.getKeyProperties().get("contig_id"));
        String contigGuidText = featureIndex.getKeyProperties().get("contig_guid");
        Assert.assertNotNull("missing contig_guid", contigGuidText);
        ObjectData contigIndex = getIndexedObject(new GUID(contigGuidText));
        Assert.assertEquals("NC_000913", "" + contigIndex.getKeyProperties().get("contig_id"));
    }

    @Test
    public void testMediaCompound() throws Exception {
        System.out.println("*** start testMediaCompound***");
        indexObject("MediaCompound", 0, "media01",
                new GUID("WS:1/1/1"), "Media.1");
        Set<GUID> guids = indexStorage.searchIds(ImmutableList.of("MediaCompound"),
                MatchFilter.getBuilder().withLookupInKey("name", "cpd00009").build(),
                null, AccessFilter.create().withAdmin(true));
        Assert.assertEquals(1, guids.size());
        ObjectData index = indexStorage.getObjectsByIds(guids).get(0);
        System.out.println("Indexed: " + index.getKeyProperties());
        Assert.assertEquals("-50.0", "" + index.getKeyProperties().get("minFlux"));
        Assert.assertEquals("50.0", "" + index.getKeyProperties().get("maxFlux"));
        Assert.assertEquals("0.001", "" + index.getKeyProperties().get("concentration"));
        System.out.println("*** end testMediaCompound***");
    }

    @Test
    public void testTaxon() throws Exception {
        System.out.println("*** start testTaxon***");
        indexObject("Taxon", 0, "taxon01", new GUID("WS:1/1/1"), "Taxon.1");
        Set<GUID> guids = indexStorage.searchIds(ImmutableList.of("Taxon"),
                MatchFilter.getBuilder().withLookupInKey("scientific_name", "Azorhizobium").build(),
                null, AccessFilter.create().withAdmin(true));
        Assert.assertEquals(1, guids.size());
        ObjectData index = indexStorage.getObjectsByIds(guids).get(0);
        System.out.println("Indexed: " + index.getKeyProperties());
        final String lineage = "cellular organisms; Bacteria; Proteobacteria; Alphaproteobacteria; " +
                "Rhizobiales; Xanthobacteraceae";
        final String aliases = "Azorhizobium Dreyfus et al. 1988 emend. Lang et al. 2013, Azotirhizobium";
        Assert.assertEquals("Bacteria", "" + index.getKeyProperties().get("domain"));
        Assert.assertEquals("Azorhizobium", "" + index.getKeyProperties().get("scientific_name"));
        Assert.assertEquals("11", "" + index.getKeyProperties().get("genetic_code"));
        Assert.assertEquals(aliases, "" + index.getKeyProperties().get("aliases"));
        Assert.assertEquals(lineage, "" + index.getKeyProperties().get("scientific_lineage"));
        System.out.println("*** end testTaxon***");
    }

    @Test
    public void testOntologyTerm() throws Exception {
        indexObject("OntologyTerm", 0, "ontology01",
                new GUID("WS:1/1/1"), "Ontology.1");
        GUID expectedGUID = new GUID("WS:1/1/1:OntologyTerm/PO:0000027");
        Set<GUID> ids = indexStorage.searchIds(ImmutableList.of("OntologyTerm"), ft("lateral"), null,
                AccessFilter.create().withAdmin(true));
        Assert.assertEquals(1, ids.size());
        GUID id = ids.iterator().next();
        Assert.assertEquals(expectedGUID, id);
        Set<Integer> accessGroupIds = new LinkedHashSet<>(Arrays.asList(1, 2, 3));
        List<ObjectData> objList = indexStorage.getObjectsByIds(
                new HashSet<>(Arrays.asList(id)));
        ObjectData index = objList.get(0);
        System.out.println("Ontology Indexed: " + index.getKeyProperties());
        final String def = "\"A root tip (PO:0000025) of a lateral root (PO:0020121).\" " +
                "[PO:austin_meier, TAIR:Katica_Ilic]";
        final String syn = "\"punta de la ra&#237z lateral (Spanish)\" EXACT Spanish " +
                "[POC:Maria_Alejandra_Gandolfo], \"(Japanese)\" EXACT Japanese [NIG:Yukiko_Yamazaki]";
        Assert.assertEquals("PO:0000027", "" + index.getKeyProperties().get("id"));
        Assert.assertEquals("lateral root tip", "" + index.getKeyProperties().get("name"));
        Assert.assertEquals("plant_anatomy", "" + index.getKeyProperties().get("namespace"));
        Assert.assertEquals(def, "" + index.getKeyProperties().get("definition"));
        Assert.assertEquals(syn, "" + index.getKeyProperties().get("synonyms"));
        Assert.assertEquals("go", "" + index.getKeyProperties().get("ontology_id"));
        Assert.assertEquals("plant_ontology", "" + index.getKeyProperties().get("ontology_name"));

    }

    @Rule
    public ExpectedException expectedException = ExpectedException.none();


    @Test
    public void testMultiTypeSearchValidation1() throws Exception {
        expectedException.expect(IllegalArgumentException.class);
        expectedException.expectMessage("Invalid list of object types. List is null.");

        // null list of types
        indexStorage.searchIds(null, MatchFilter.getBuilder().build(),
                null, AccessFilter.create().withAdmin(true));
    }

    @Test
    public void testMultiTypeSearchValidation2() throws Exception {
        indexObject("Genome", 0, "genome01",
                new GUID("WS:1/1/1"), "MyGenome.1");

        // empty list
        Set<GUID> guids = indexStorage.searchIds(new ArrayList<String>(),
                MatchFilter.getBuilder().build(),
                null, AccessFilter.create().withAdmin(true));

        // genome object + 2 parent guids (see prepare)
        assertThat("incorrect number of results", guids.size(), is(3));
    }

    @Test
    public void testMultiTypeSearchValidation3() throws Exception {
        expectedException.expect(IOException.class);
        expectedException.expectMessage("Invalid list of object types. " +
                "Contains one or more null elements.");

        // list containing a mix of null and non-null types
        List<String> objectTypes = new ArrayList<>();
        objectTypes.add(null);
        objectTypes.add("Narrative");
        indexStorage.searchIds(objectTypes,
                MatchFilter.getBuilder().build(),
                null, AccessFilter.create().withAdmin(true));
    }

    @Test
    public void testMultiTypeSearchValidation4() throws Exception {
        indexObject("Genome", 0, "genome01",
                new GUID("WS:1/1/1"), "MyGenome.1");
        List<String> objectTypes;
        Set<GUID> guids;

        // list with single non-null element
        objectTypes = new ArrayList<>();
        objectTypes.add("Genome");
        guids = indexStorage.searchIds(objectTypes,
                MatchFilter.getBuilder().build(),
                null, AccessFilter.create().withAdmin(true));

        assertThat("incorrect number of results", guids.size(), is(1));
    }

    @Test
    public void testMultiTypeSearchValidation5() throws Exception {
        expectedException.expect(IOException.class);
        expectedException.expectMessage("Invalid list of object types. " +
                "List size exceeds maximum limit of 50");

        // list exceeding max size
        List<String> objectTypes = new ArrayList<>();
        for(int ii=0; ii< ElasticIndexingStorage.MAX_OBJECT_TYPES_SIZE+1; ii++) {
            objectTypes.add("Narrative");
        }

        indexStorage.searchIds(objectTypes,
                MatchFilter.getBuilder().build(),
                null, AccessFilter.create().withAdmin(true));
    }

    @Test
    public void testMultiTypeSearch() throws Exception {
        // search for Genome objects
        indexObject("Genome", 0, "genome01",
                new GUID("WS:1/3/1"), "MyGenome.1");

        Set<GUID> guids;

        guids = indexStorage.searchIds(ImmutableList.of("Genome"),
                MatchFilter.getBuilder().build(),
                null, AccessFilter.create().withAdmin(true));

        assertThat("result missing expected object", guids.contains(GUID.fromRef("WS", "1/3/1")),
                                                                                            is(true));
        assertThat("incorrect number of objects in results", guids.size(), is(1));

        // search for Genome and Assembly objects (assembly object from prepare method)
        guids = indexStorage.searchIds(ImmutableList.of("Genome", "Assembly"),
                MatchFilter.getBuilder().build(),
                null, AccessFilter.create().withAdmin(true));

        assertThat("result missing expected object", guids.contains(GUID.fromRef("WS", "1/3/1")),
                is(true));

        assertThat("result missing expected object", guids.contains(GUID.fromRef("WS", "1/2/1")),
                is(true));
        assertThat("incorrect number of objects in results", guids.size(), is(2));


        // search for Genome, Assembly and AssemblyContig objects (assembly and contig objects from prepare method)
        guids = indexStorage.searchIds(ImmutableList.of("Genome", "Assembly", "AssemblyContig"),
                MatchFilter.getBuilder().build(),
                null, AccessFilter.create().withAdmin(true));

        assertThat("result missing expected object", guids.contains(GUID.fromRef("WS", "1/3/1")),
                is(true));

        assertThat("result missing expected object", guids.contains(GUID.fromRef("WS", "1/2/1")),
                is(true));
        assertThat("result missing expected object",
                guids.contains(GUID.fromRef("WS", "WS:1/2/1:contig/NC_000913")),
                is(true));
        assertThat("incorrect number of objects in results", guids.size(), is(3));
    }

    @Test
    public void testVersions() throws Exception {
        SearchObjectType objType = new SearchObjectType("Simple", 1);
        List<String> type = ImmutableList.of(objType.getType());
        IndexingRules ir = IndexingRules.fromPath(new ObjectJsonPath("prop1"))
                .withFullText().build();
        final ObjectTypeParsingRules rule = ObjectTypeParsingRules.getBuilder(
                objType, new StorageObjectType("foo", "bar"))
                .withIndexingRule(ir).build();
        GUID id11 = new GUID("WS:2/1/1");
        indexObject(id11, rule, "{\"prop1\":\"abc 123\"}", "obj.1", Instant.now(), null, false);
        checkIdInSet(indexStorage.searchIds(type, ft("abc"), null, 
                AccessFilter.create().withAccessGroups(2)), 1, id11);
        GUID id2 = new GUID("WS:2/2/1");
        indexObject(id2, rule, "{\"prop1\":\"abd\"}", "obj.2", Instant.now(), null, false);
        GUID id3 = new GUID("WS:3/1/1");
        indexObject(id3, rule, "{\"prop1\":\"abc\"}", "obj.3", Instant.now(), null, false);
        checkIdInSet(indexStorage.searchIds(type, ft("abc"), null, 
                AccessFilter.create().withAccessGroups(2)), 1, id11);
        GUID id12 = new GUID("WS:2/1/2");
        indexObject(id12, rule, "{\"prop1\":\"abc 124\"}", "obj.1", Instant.now(), null, false);
        checkIdInSet(indexStorage.searchIds(type, ft("abc"), null,
                AccessFilter.create().withAccessGroups(2)), 1, id12);
        GUID id13 = new GUID("WS:2/1/3");
        indexObject(id13, rule, "{\"prop1\":\"abc 125\"}", "obj.1", Instant.now(), null, false);
        //indexStorage.refreshIndex(indexStorage.getIndex(objType));
        checkIdInSet(indexStorage.searchIds(type, ft("abc"), null,
                AccessFilter.create().withAccessGroups(2)), 1, id13);
        checkIdInSet(indexStorage.searchIds(type, ft("125"), null,
                AccessFilter.create().withAccessGroups(2)), 1, id13);
        Assert.assertEquals(0, indexStorage.searchIds(type, ft("123"), null,
                AccessFilter.create().withAccessGroups(2)).size());
        checkIdInSet(indexStorage.searchIds(type, ft("abd"), null,
                AccessFilter.create().withAccessGroups(2)), 1, id2);
        checkIdInSet(indexStorage.searchIds(type, ft("abc"), null,
                AccessFilter.create().withAccessGroups(3)), 1, id3);
        // With all history
        Assert.assertEquals(1, indexStorage.searchIds(type, ft("123"), null,
                AccessFilter.create().withAccessGroups(2).withAllHistory(true)).size());
        Assert.assertEquals(3, indexStorage.searchIds(type, ft("abc"), null,
                AccessFilter.create().withAccessGroups(2).withAllHistory(true)).size());
    }

    private Set<GUID> lookupIdsByKey(List<String> objTypes, String keyName, Object value,
            AccessFilter af) throws IOException {
        Set<GUID> ret = indexStorage.searchIds(objTypes, MatchFilter.getBuilder().withLookupInKey(
                keyName, new MatchValue(value)).build(), null, af);
        PostProcessing pp = new PostProcessing();
        pp.objectData = true;
        pp.objectKeys = true;
        indexStorage.getObjectsByIds(ret, pp);
        return ret;
    }

    @Test
    public void testSharing() throws Exception {
        SearchObjectType objType = new SearchObjectType("Sharable",1 );
        List<String> type = ImmutableList.of(objType.getType());
        IndexingRules ir = IndexingRules.fromPath(new ObjectJsonPath("prop2"))
                .withKeywordType("integer").build();
        final ObjectTypeParsingRules rule = ObjectTypeParsingRules.getBuilder(
                objType, new StorageObjectType("foo", "bar"))
                .withIndexingRule(ir).build();
        GUID id1 = new GUID("WS:10/1/1");
        indexObject(id1, rule, "{\"prop2\": 123}", "obj.1", Instant.now(), null, false);
        GUID id2 = new GUID("WS:10/1/2");
        indexObject(id2, rule, "{\"prop2\": 124}", "obj.1", Instant.now(), null, false);
        GUID id3 = new GUID("WS:10/1/3");
        indexObject(id3, rule, "{\"prop2\": 125}", "obj.1", Instant.now(), null, false);
        AccessFilter af10 = AccessFilter.create().withAccessGroups(10);
        Assert.assertEquals(0, lookupIdsByKey(type, "prop2", 123, af10).size());
        checkIdInSet(lookupIdsByKey(type, "prop2", 125, af10), 1, id3);
        indexStorage.shareObjects(new LinkedHashSet<>(Arrays.asList(id1)), 11, false);
        AccessFilter af11 = AccessFilter.create().withAccessGroups(11);
        checkIdInSet(lookupIdsByKey(type, "prop2", 123, af11), 1, id1);
        checkIdInSet(lookupIdsByKey(type, "prop2", 125, af10), 1, id3);
        Assert.assertEquals(0, lookupIdsByKey(type, "prop2", 124, af11).size());
        checkIdInSet(lookupIdsByKey(type, "prop2", 124,
                AccessFilter.create().withAccessGroups(10).withAllHistory(true)), 1, id2);
        Assert.assertEquals(0, lookupIdsByKey(type, "prop2", 125, af11).size());
        indexStorage.shareObjects(new LinkedHashSet<>(Arrays.asList(id2)), 11, false);
        Assert.assertEquals(0, lookupIdsByKey(type, "prop2", 123, af11).size());
        checkIdInSet(lookupIdsByKey(type, "prop2", 124, af11), 1, id2);
        Assert.assertEquals(0, lookupIdsByKey(type, "prop2", 125, af11).size());
        indexStorage.unshareObjects(new LinkedHashSet<>(Arrays.asList(id2)), 11);
        Assert.assertEquals(0, lookupIdsByKey(type, "prop2", 123, af11).size());
        Assert.assertEquals(0, lookupIdsByKey(type, "prop2", 124, af11).size());
        Assert.assertEquals(0, lookupIdsByKey(type, "prop2", 125, af11).size());
        indexStorage.shareObjects(new LinkedHashSet<>(Arrays.asList(id1)), 11, false);
        indexStorage.shareObjects(new LinkedHashSet<>(Arrays.asList(id2)), 12, false);
        AccessFilter af1x = AccessFilter.create().withAccessGroups(11, 12);
        checkIdInSet(lookupIdsByKey(type, "prop2", 123, af1x), 1, id1);
        checkIdInSet(lookupIdsByKey(type, "prop2", 124, af1x), 1, id2);
        Assert.assertEquals(0, lookupIdsByKey(type, "prop2", 125, af1x).size());
        indexStorage.unshareObjects(new LinkedHashSet<>(Arrays.asList(id1)), 11);
        Assert.assertEquals(0, lookupIdsByKey(type, "prop2", 123, af1x).size());
        checkIdInSet(lookupIdsByKey(type, "prop2", 124, af1x), 1, id2);
        Assert.assertEquals(0, lookupIdsByKey(type, "prop2", 125, af1x).size());
        indexStorage.unshareObjects(new LinkedHashSet<>(Arrays.asList(id2)), 12);
        Assert.assertEquals(0, lookupIdsByKey(type, "prop2", 123, af1x).size());
        Assert.assertEquals(0, lookupIdsByKey(type, "prop2", 124, af1x).size());
        Assert.assertEquals(0, lookupIdsByKey(type, "prop2", 125, af1x).size());
    }

    @Test
    public void testPublic() throws Exception {
        SearchObjectType objType = new SearchObjectType("Publishable", 1);
        List<String> type = ImmutableList.of(objType.getType());
        IndexingRules ir = IndexingRules.fromPath(new ObjectJsonPath("prop3"))
                .withFullText().build();
        final ObjectTypeParsingRules rule = ObjectTypeParsingRules.getBuilder(
                objType, new StorageObjectType("foo", "bar"))
                .withIndexingRule(ir).build();
        GUID id1 = new GUID("WS:20/1/1");
        GUID id2 = new GUID("WS:20/2/1");
        indexObject(id1, rule, "{\"prop3\": \"private gggg\"}", "obj.1", Instant.now(), null,
                false);
        indexObject(id2, rule, "{\"prop3\": \"public gggg\"}", "obj.2", Instant.now(), null, true);
        Assert.assertEquals(0, lookupIdsByKey(type, "prop3", "private",
                AccessFilter.create().withPublic(true)).size());
        checkIdInSet(lookupIdsByKey(type, "prop3", "private",
                AccessFilter.create().withAccessGroups(20).withPublic(true)), 1, id1);
        Assert.assertEquals(0, lookupIdsByKey(type, "prop3", "private",
                AccessFilter.create().withAccessGroups(21).withPublic(true)).size());
        checkIdInSet(lookupIdsByKey(type, "prop3", "public",
                AccessFilter.create().withAccessGroups(21).withPublic(true)), 1, id2);
        indexStorage.publishObjects(new LinkedHashSet<>(Arrays.asList(id1)));
        checkIdInSet(lookupIdsByKey(type, "prop3", "private",
                AccessFilter.create().withAccessGroups(20).withPublic(true)), 1, id1);
        checkIdInSet(lookupIdsByKey(type, "prop3", "private",
                AccessFilter.create().withAccessGroups(21).withPublic(true)), 1, id1);
        indexStorage.unpublishObjects(new LinkedHashSet<>(Arrays.asList(id1)));
        checkIdInSet(lookupIdsByKey(type, "prop3", "private",
                AccessFilter.create().withAccessGroups(20).withPublic(true)), 1, id1);
        Assert.assertEquals(0, lookupIdsByKey(type, "prop3", "private",
                AccessFilter.create().withAccessGroups(21).withPublic(true)).size());
        checkIdInSet(lookupIdsByKey(type, "prop3", "public",
                AccessFilter.create().withAccessGroups(21).withPublic(true)), 1, id2);
        indexStorage.unpublishObjects(new LinkedHashSet<>(Arrays.asList(id2)));
        checkIdInSet(lookupIdsByKey(type, "prop3", "private",
                AccessFilter.create().withAccessGroups(20).withPublic(true)), 1, id1);
        checkIdInSet(lookupIdsByKey(type, "prop3", "public",
                AccessFilter.create().withAccessGroups(20).withPublic(true)), 1, id2);
        Assert.assertEquals(0, lookupIdsByKey(type, "prop3", "private",
                AccessFilter.create().withAccessGroups(21).withPublic(true)).size());
        Assert.assertEquals(0, lookupIdsByKey(type, "prop3", "public",
                AccessFilter.create().withAccessGroups(21).withPublic(true)).size());
    }

    private static Set<GUID> asSet(GUID... guids) {
        return new LinkedHashSet<>(Arrays.asList(guids));
    }

    private static void checkIdInSet(Set<GUID> ids, int size, GUID id) {
        Assert.assertEquals("Set contains: " + ids, size, ids.size());
        Assert.assertTrue("Set contains: " + ids, ids.contains(id));
    }

    @Test
    public void testPublicDataPalettes() throws Exception {
        SearchObjectType objType = new SearchObjectType("ShareAndPublic", 1);
        List<String> type = ImmutableList.of(objType.getType());
        IndexingRules ir = IndexingRules.fromPath(new ObjectJsonPath("prop4"))
                .withKeywordType("integer").build();
        final ObjectTypeParsingRules rule = ObjectTypeParsingRules.getBuilder(
                objType, new StorageObjectType("foo", "bar"))
                .withIndexingRule(ir).build();
        GUID id1 = new GUID("WS:30/1/1");
        indexObject(id1, rule, "{\"prop4\": 123}", "obj.1", Instant.now(), null, false);
        AccessFilter af30 = AccessFilter.create().withAccessGroups(30);
        checkIdInSet(lookupIdsByKey(type, "prop4", 123, af30), 1, id1);
        AccessFilter afPub = AccessFilter.create().withPublic(true);
        Assert.assertEquals(0, lookupIdsByKey(type, "prop4", 123, afPub).size());
        // Let's share object id1 with PUBLIC workspace 31
        indexStorage.shareObjects(new LinkedHashSet<>(Arrays.asList(id1)), 31, true);
        // Should be publicly visible
        checkIdInSet(lookupIdsByKey(type, "prop4", 123, afPub), 1, id1);
        // Let's check that unshare (with no call to unpublishObjectsExternally is enough
        indexStorage.unshareObjects(new LinkedHashSet<>(Arrays.asList(id1)), 31);
        // Should NOT be publicly visible
        Assert.assertEquals(0, lookupIdsByKey(type, "prop4", 123, afPub).size());
        // Let's share object id1 with NOT public workspace 31
        indexStorage.shareObjects(new LinkedHashSet<>(Arrays.asList(id1)), 31, false);
        // Should NOT be publicly visible
        Assert.assertEquals(0, lookupIdsByKey(type, "prop4", 123, afPub).size());
        // Now let's declare workspace 31 PUBLIC
        indexStorage.publishObjectsExternally(asSet(id1), 31);
        // Should be publicly visible
        checkIdInSet(lookupIdsByKey(type, "prop4", 123, afPub), 1, id1);
        // Now let's declare workspace 31 NOT public
        indexStorage.unpublishObjectsExternally(asSet(id1), 31);
        // Should NOT be publicly visible
        Assert.assertEquals(0, lookupIdsByKey(type, "prop4", 123, afPub).size());
    }

    @Test
    public void testDeleteUndelete() throws Exception {
        SearchObjectType objType = new SearchObjectType("DelUndel", 1);
        List<String> type = ImmutableList.of(objType.getType());
        IndexingRules ir = IndexingRules.fromPath(new ObjectJsonPath("myprop"))
                .withFullText().build();
        final ObjectTypeParsingRules rule = ObjectTypeParsingRules.getBuilder(
                objType, new StorageObjectType("foo", "bar"))
                .withIndexingRule(ir).build();
        GUID id1 = new GUID("WS:100/2/1");
        GUID id2 = new GUID("WS:100/2/2");
        indexObject(id1, rule, "{\"myprop\": \"some stuff\"}", "myobj", Instant.now(), null,
                false);
        indexObject(id2, rule, "{\"myprop\": \"some other stuff\"}", "myobj", Instant.now(), null,
                false);
        
        final AccessFilter filter = AccessFilter.create().withAccessGroups(100);
        final AccessFilter filterAllVers = AccessFilter.create().withAccessGroups(100)
                .withAllHistory(true);

        // check ids show up before delete
        assertThat("incorrect ids returned", lookupIdsByKey(type, "myprop", "some",
                filter), is(set(id2)));
        assertThat("incorrect ids returned", lookupIdsByKey(type, "myprop", "some",
                filterAllVers), is(set(id1, id2)));

        // check ids show up correctly after delete
        indexStorage.deleteAllVersions(id1);
        indexStorage.refreshIndexByType(rule);
        assertThat("incorrect ids returned", lookupIdsByKey(type, "myprop", "some",
                filter), is(set()));
        //TODO NOW these should probaby not show up
        assertThat("incorrect ids returned", lookupIdsByKey(type, "myprop", "some",
                filterAllVers), is(set(id1, id2)));

        // check ids restored after undelete
        indexStorage.undeleteAllVersions(id1);
        indexStorage.refreshIndexByType(rule);
        assertThat("incorrect ids returned", lookupIdsByKey(type, "myprop", "some",
                filter), is(set(id2)));
        assertThat("incorrect ids returned", lookupIdsByKey(type, "myprop", "some",
                filterAllVers), is(set(id1, id2)));

        /* This doesn't actually test that the access group id is removed from the access
         * doc AFAIK, but I don't think that matters.
         */
    }

    @Test
    public void testPublishAllVersions() throws Exception {
        // tests the all versions method for setting objects public / non-public.
        SearchObjectType objType = new SearchObjectType("PublishAllVersions", 1);
        List<String> type = ImmutableList.of(objType.getType());
        IndexingRules ir = IndexingRules.fromPath(new ObjectJsonPath("myprop"))
                .withFullText().build();
        final ObjectTypeParsingRules rule = ObjectTypeParsingRules.getBuilder(
                objType, new StorageObjectType("foo", "bar"))
                .withIndexingRule(ir).build();
        GUID id1 = new GUID("WS:200/2/1");
        GUID id2 = new GUID("WS:200/2/2");
        indexObject(id1, rule, "{\"myprop\": \"some stuff\"}", "myobj", Instant.now(), null,
                false);
        indexObject(id2, rule, "{\"myprop\": \"some other stuff\"}", "myobj", Instant.now(),
                null, false);
        
        final AccessFilter filter = AccessFilter.create()
                .withAllHistory(true).withPublic(false);
        final AccessFilter filterPublic = AccessFilter.create()
                .withAllHistory(true).withPublic(true);

        // check ids show up before publish
        assertThat("incorrect ids returned", lookupIdsByKey(type, "myprop", "some",
                filter), is(set()));
        assertThat("incorrect ids returned", lookupIdsByKey(type, "myprop", "some",
                filterPublic), is(set()));

        // check ids show up correctly after publish
        indexStorage.publishAllVersions(id1);
        indexStorage.refreshIndexByType(rule);
        assertThat("incorrect ids returned", lookupIdsByKey(type, "myprop", "some",
                filter), is(set()));
        //TODO NOW these should probaby not show up
        assertThat("incorrect ids returned", lookupIdsByKey(type, "myprop", "some",
                filterPublic), is(set(id1, id2)));

        // check ids hidden after unpublish
        indexStorage.unpublishAllVersions(id1);
        indexStorage.refreshIndexByType(rule);
        assertThat("incorrect ids returned", lookupIdsByKey(type, "myprop", "some",
                filter), is(set()));
        assertThat("incorrect ids returned", lookupIdsByKey(type, "myprop", "some",
                filterPublic), is(set()));
    }
    
    @Test
    public void testTypeVersions() throws Exception {
        /* test that types with incompatible fields but different versions index successfully. */
        final SearchObjectType type1 = new SearchObjectType("TypeVers", 5);
        // changing 10 -> 5 makes the test fail due to elasticsearch exception
        final SearchObjectType type2 = new SearchObjectType("TypeVers", 10);
        final IndexingRules idxRules1 = IndexingRules.fromPath(new ObjectJsonPath("bar"))
                .withKeywordType("integer").build();
        final IndexingRules idxRules2 = IndexingRules.fromPath(new ObjectJsonPath("bar"))
                .withKeywordType("keyword").build();
        final Instant now = Instant.now();
        final ObjectTypeParsingRules rule1 = ObjectTypeParsingRules.getBuilder(
                type1, new StorageObjectType("foo", "bar"))
                .withIndexingRule(idxRules1).build();
        final ObjectTypeParsingRules rule2 = ObjectTypeParsingRules.getBuilder(
                type2, new StorageObjectType("foo", "bar"))
                .withIndexingRule(idxRules2).build();
        
        indexObject(new GUID("WS:1/2/3"), rule1, "{\"bar\": 1}", "o1", now,
                null, false);
        indexObject(new GUID("WS:4/5/6"), rule2, "{\"bar\": \"whee\"}", "o2", now,
                null, false);
        
        final ObjectData indexedObj1 =
                indexStorage.getObjectsByIds(TestCommon.set(new GUID("WS:1/2/3"))).get(0);
        
        final ObjectData expected1 = ObjectData.getBuilder(new GUID("WS:1/2/3"), type1)
                .withNullableObjectName("o1")
                .withNullableCreator("creator")
                .withNullableTimestamp(indexedObj1.getTimestamp().get())
                .withNullableData(ImmutableMap.of("bar", 1))
                .withKeyProperty("bar", "1")
                .build();
        
        //due to potential truncation of timestamp on mac
        TestCommon.assertCloseMS(indexedObj1.getTimestamp().get(), now, 0, 10);

        assertThat("incorrect indexed object", indexedObj1, is(expected1));
        
        final ObjectData indexedObj2 =
                indexStorage.getObjectsByIds(TestCommon.set(new GUID("WS:4/5/6"))).get(0);

        final ObjectData expected2 = ObjectData.getBuilder(new GUID("WS:4/5/6"), type2)
                .withNullableObjectName("o2")
                .withNullableCreator("creator")
                .withNullableTimestamp(indexedObj2.getTimestamp().get())
                .withNullableData(ImmutableMap.of("bar", "whee"))
                .withKeyProperty("bar", "whee")
                .build();

        TestCommon.assertCloseMS(indexedObj2.getTimestamp().get(), now, 0, 10);

        assertThat("incorrect indexed object", indexedObj2, is(expected2));
        
    }
    
    @Test
    public void noIndexingRules() throws Exception {
        indexStorage.indexObjects(
                ObjectTypeParsingRules.getBuilder(
                        new SearchObjectType("NoIndexingRules", 1),
                        new StorageObjectType("foo", "bar"))
                        .build(),
                SourceData.getBuilder(new UObject(new HashMap<>()), "objname", "creator")
                        .withNullableCommitHash("commit")
                        .withNullableCopier("cop")
                        .withNullableMD5("emmdeefive")
                        .withNullableMethod("meth")
                        .withNullableModule("mod")
                        .withNullableVersion("ver")
                        .build(),
                Instant.ofEpochMilli(10000),
                null,
                new GUID("WS:1000/1/1"),
                Collections.emptyMap(),
                false);
        
        final ObjectData indexedObj =
                indexStorage.getObjectsByIds(TestCommon.set(new GUID("WS:1000/1/1"))).get(0);
        
        final ObjectData expected = ObjectData.getBuilder(
                new GUID("WS:1000/1/1"), new SearchObjectType("NoIndexingRules", 1))
                .withNullableObjectName("objname")
                .withNullableCreator("creator")
                .withNullableCommitHash("commit")
                .withNullableCopier("cop")
                .withNullableMD5("emmdeefive")
                .withNullableMethod("meth")
                .withNullableModule("mod")
                .withNullableModuleVersion("ver")
                .withNullableTimestamp(Instant.ofEpochMilli(10000))
                .build();
        
        assertThat("incorrect indexed object", indexedObj, is(expected));
    }
    
    @Test
    public void excludeSubObjects() throws Exception {
        // regular object
        indexStorage.indexObjects(
                ObjectTypeParsingRules.getBuilder(
                        new SearchObjectType("ExcludeSubObjectsNorm", 1),
                        new StorageObjectType("foo", "bar"))
                        .withIndexingRule(IndexingRules.fromPath(new ObjectJsonPath("whee"))
                                .build())
                        .build(),
                SourceData.getBuilder(new UObject(new HashMap<>()), "objname", "creator").build(),
                Instant.ofEpochMilli(10000),
                null,
                new GUID("WS:2000/1/1"),
                ImmutableMap.of(new GUID("WS:2000/1/1"), new ParsedObject(
                        "{\"whee\": \"imaprettypony\"}",
                        ImmutableMap.of("whee", Arrays.asList("imaprettypony")))),
                false);
        
        // sub object
        indexStorage.indexObjects(
                ObjectTypeParsingRules.getBuilder(
                        new SearchObjectType("ExcludeSubObjectsSub", 1),
                        new StorageObjectType("foo", "bar"))
                        .toSubObjectRule(
                                "sub", new ObjectJsonPath("subpath"), new ObjectJsonPath("id"))
                        .withIndexingRule(IndexingRules.fromPath(new ObjectJsonPath("whee"))
                                .build())
                        .build(),
                SourceData.getBuilder(new UObject(new HashMap<>()), "objname", "creator").build(),
                Instant.ofEpochMilli(10000),
                null,
                new GUID("WS:2000/2/1"),
                ImmutableMap.of(new GUID("WS:2000/2/1:sub/yay"), new ParsedObject(
                        "{\"whee\": \"imaprettypony\"}",
                        ImmutableMap.of("whee", Arrays.asList("imaprettypony")))),
                false);
        
        final Set<GUID> res = indexStorage.searchIds(
                Collections.emptyList(),
                MatchFilter.getBuilder().withNullableFullTextInAll("imaprettypony").build(),
                null,
                AccessFilter.create().withAccessGroups(2000));
        assertThat("incorrect objects found", res,
                is(set(new GUID("WS:2000/1/1"), new GUID("WS:2000/2/1:sub/yay"))));
        
        final Set<GUID> res2 = indexStorage.searchIds(
                Collections.emptyList(),
                MatchFilter.getBuilder().withNullableFullTextInAll("imaprettypony")
                        .withExcludeSubObjects(true).build(),
                null,
                AccessFilter.create().withAccessGroups(2000));
        assertThat("incorrect objects found", res2, is(set(new GUID("WS:2000/1/1"))));
        
        final Map<String, Integer> count = indexStorage.searchTypes(
                MatchFilter.getBuilder().withNullableFullTextInAll("imaprettypony").build(),
                AccessFilter.create().withAccessGroups(2000));
        
        assertThat("incorrect type count", count, is(ImmutableMap.of(
                "ExcludeSubObjectsSub", 1,
                "ExcludeSubObjectsNorm", 1)));
        
        final Map<String, Integer> count2 = indexStorage.searchTypes(
                MatchFilter.getBuilder().withNullableFullTextInAll("imaprettypony")
                        .withExcludeSubObjects(true).build(),
                AccessFilter.create().withAccessGroups(2000));
        
        assertThat("incorrect type count", count2, is(ImmutableMap.of(
                "ExcludeSubObjectsNorm", 1)));
    }
    
    @Test
    public void sourceTags() throws Exception {
        /* tests that objects are excluded that don't share at least one tag with the requested
         * tags
         */
        indexStorage.indexObjects(
                ObjectTypeParsingRules.getBuilder(
                        new SearchObjectType("SourceTags", 1),
                        new StorageObjectType("foo", "bar"))
                        .withIndexingRule(IndexingRules.fromPath(new ObjectJsonPath("whee"))
                                .build())
                        .build(),
                SourceData.getBuilder(new UObject(new HashMap<>()), "objname", "creator")
                        .withSourceTag("refdata")
                        .withSourceTag("testnarr")
                        .build(),
                Instant.ofEpochMilli(10000),
                null,
                new GUID("WS:2000/1/1"),
                ImmutableMap.of(new GUID("WS:2000/1/1"), new ParsedObject(
                        "{\"whee\": \"imaprettypony\"}",
                        ImmutableMap.of("whee", Arrays.asList("imaprettypony")))),
                false);
        
        indexStorage.indexObjects(
                ObjectTypeParsingRules.getBuilder(
                        new SearchObjectType("SourceTags", 1),
                        new StorageObjectType("foo", "bar"))
                        .withIndexingRule(IndexingRules.fromPath(new ObjectJsonPath("whee"))
                                .build())
                        .build(),
                SourceData.getBuilder(new UObject(new HashMap<>()), "objname", "creator")
                        .withSourceTag("refdata")
                        .withSourceTag("narrative")
                        .build(),
                Instant.ofEpochMilli(10000),
                null,
                new GUID("WS:2000/2/1"),
                ImmutableMap.of(new GUID("WS:2000/2/1"), new ParsedObject(
                        "{\"whee\": \"imaprettypony\"}",
                        ImmutableMap.of("whee", Arrays.asList("imaprettypony")))),
                false);
        
        // check tags are in returned data
        final ObjectData indexedObj =
                indexStorage.getObjectsByIds(TestCommon.set(new GUID("WS:2000/1/1"))).get(0);
        
        final ObjectData expected = ObjectData.getBuilder(
                new GUID("WS:2000/1/1"), new SearchObjectType("SourceTags", 1))
                .withNullableObjectName("objname")
                .withNullableCreator("creator")
                .withSourceTag("refdata")
                .withSourceTag("testnarr")
                .withNullableTimestamp(Instant.ofEpochMilli(10000))
                .withKeyProperty("whee", "imaprettypony")
                .withNullableData(ImmutableMap.of("whee", "imaprettypony"))
                .build();
        
        assertThat("incorrect indexed object", indexedObj, is(expected));
        
        // whitelisted tags
        checkWithTags(set(), set(new GUID("WS:2000/1/1"), new GUID("WS:2000/2/1")));
        
        checkWithTags(set("refdata", "foo"),
                set(new GUID("WS:2000/1/1"), new GUID("WS:2000/2/1")));
        
        checkWithTags(set("narrative", "foo"), set(new GUID("WS:2000/2/1")));
        
        checkWithTags(set("bar", "foo"), set());
        
        
        //blacklisted tags
        checkWithTags(set(), set(new GUID("WS:2000/1/1"), new GUID("WS:2000/2/1")), true);
        
        checkWithTags(set("refdata", "foo"), set(), true);
        
        checkWithTags(set("narrative", "foo"), set(new GUID("WS:2000/1/1")), true);

        checkWithTags(set("bar", "foo"), set(new GUID("WS:2000/1/1"), new GUID("WS:2000/2/1")),
                true);
    }

    private void checkWithTags(final Set<String> tags, final Set<GUID> guids)
            throws Exception {
        checkWithTags(tags, guids, false);
    }
    
    private void checkWithTags(
            final Set<String> tags,
            final Set<GUID> guids,
            final boolean isBlacklist)
            throws Exception {
        final Builder mfer = MatchFilter.getBuilder().withIsSourceTagsBlackList(isBlacklist);
        tags.stream().forEach(t -> mfer.withSourceTag(t));
        final Set<GUID> res = indexStorage.searchIds(
                Collections.emptyList(),
                mfer.build(),
                null,
                AccessFilter.create().withAccessGroups(2000));
        assertThat("incorrect objects found", res, is(guids));
    }

    @Test
    public void sort() throws Exception {
        indexStorage.indexObjects(
                ObjectTypeParsingRules.getBuilder(
                        new SearchObjectType("Sort", 1),
                        new StorageObjectType("foo", "bar"))
                        .withIndexingRule(IndexingRules.fromPath(new ObjectJsonPath("whee"))
                                .build())
                        .build(),
                SourceData.getBuilder(new UObject(new HashMap<>()), "my name", "creator")
                        .withNullableMethod("my method")
                        .build(),
                Instant.ofEpochMilli(10000),
                null,
                new GUID("WS:1/1/1"),
                ImmutableMap.of(new GUID("WS:1/1/1"), new ParsedObject(
                        "{\"whee\": \"imaprettypony\"}",
                        ImmutableMap.of("whee", Arrays.asList("imaprettypony")))),
                false);

        indexStorage.indexObjects(
                ObjectTypeParsingRules.getBuilder(
                        new SearchObjectType("Sort", 1),
                        new StorageObjectType("foo", "bar"))
                        .withIndexingRule(IndexingRules.fromPath(new ObjectJsonPath("whee"))
                                .build())
                        .build(),
                SourceData.getBuilder(new UObject(new HashMap<>()), "a name", "creator")
                        .withNullableMethod("a method")
                        .build(),
                Instant.ofEpochMilli(20000),
                null,
                new GUID("WS:1/2/1"),
                ImmutableMap.of(new GUID("WS:1/2/1"), new ParsedObject(
                        "{\"whee\": \"imaprettypony\"}",
                        ImmutableMap.of("whee", Arrays.asList("imaprettypony")))),
                false);

        indexStorage.indexObjects(
                ObjectTypeParsingRules.getBuilder(
                        new SearchObjectType("Sort", 1),
                        new StorageObjectType("foo", "bar"))
                        .withIndexingRule(IndexingRules.fromPath(new ObjectJsonPath("whee"))
                                .build())
                        .build(),
                SourceData.getBuilder(new UObject(new HashMap<>()), "zoo", "creator")
                .withNullableMethod("zoo method")
                        .build(),
                Instant.ofEpochMilli(10000),
                null,
                new GUID("WS:1/3/1"),
                ImmutableMap.of(new GUID("WS:1/3/1"), new ParsedObject(
                        "{\"whee\": \"in bruges\"}",
                        ImmutableMap.of("whee", Arrays.asList("in bruges")))),
                false);


        indexStorage.indexObjects(
                ObjectTypeParsingRules.getBuilder(
                        new SearchObjectType("Sort", 1),
                        new StorageObjectType("foo", "bar"))
                        .withIndexingRule(IndexingRules.fromPath(new ObjectJsonPath("whee"))
                                .build())
                        .build(),
                SourceData.getBuilder(new UObject(new HashMap<>()), "crumbs", "creator")
                        .withNullableMethod("crummy method")
                        .build(),
                Instant.ofEpochMilli(20000),
                null,
                new GUID("WS:1/4/1"),
                ImmutableMap.of(new GUID("WS:1/4/1"), new ParsedObject(
                        "{\"whee\": \"in bruges\"}",
                        ImmutableMap.of("whee", Arrays.asList("in bruges")))),
                false);

        final PostProcessing pp = new PostProcessing();
        pp.objectData = true;

        final List<ObjectData> ret = indexStorage.searchObjects(
                Collections.emptyList(),
                MatchFilter.getBuilder().build(),
                Arrays.asList(SortingRule.getKeyPropertyBuilder("whee").build(),
                        SortingRule.getStandardPropertyBuilder("provenance_method").build()),
                AccessFilter.create().withAccessGroups(1),
                null,
                pp)
                .objects;

        final List<GUID> guids = ret.stream().map(od -> od.getGUID()).collect(Collectors.toList());

        assertThat("incorrect sort order", guids, is(Arrays.asList(new GUID("WS:1/2/1"),
                new GUID("WS:1/1/1"), new GUID("WS:1/4/1"), new GUID("WS:1/3/1"))));

        final List<ObjectData> ret1 = indexStorage.searchObjects(
                Collections.emptyList(),
                MatchFilter.getBuilder().build(),
                Arrays.asList(SortingRule.getKeyPropertyBuilder("whee")
                                .withNullableIsAscending(false).build(),
                        SortingRule.getStandardPropertyBuilder("provenance_method").build()),
                AccessFilter.create().withAccessGroups(1),
                null,
                pp)
                .objects;

        final List<GUID> guids1 = ret1.stream().map(od -> od.getGUID())
                .collect(Collectors.toList());

        assertThat("incorrect sort order", guids1, is(Arrays.asList(new GUID("WS:1/4/1"),
                new GUID("WS:1/3/1"), new GUID("WS:1/2/1"), new GUID("WS:1/1/1"))));
    }

    @Test
    public void sortFail() {
        try {
            final PostProcessing pp = new PostProcessing();
            pp.objectData = true;
            indexStorage.searchObjects(
                    Collections.emptyList(),
                    MatchFilter.getBuilder().build(),
                    Arrays.asList(SortingRule.getKeyPropertyBuilder("whee")
                                    .withNullableIsAscending(false).build(),
                            SortingRule.getStandardPropertyBuilder("bad key").build()),
                    AccessFilter.create().withAccessGroups(1),
                    null,
                    pp);
            fail("expected exception");
        } catch (Exception got) {
            TestCommon.assertExceptionCorrect(got, new IllegalArgumentException(
                    "Unknown object property bad key"));
        }
    }

    private void prepareTestMultiwordSearch(GUID guid1, GUID guid2, GUID guid3) throws Exception {
        final SearchObjectType objectType = new SearchObjectType("Simple", 1);
        final IndexingRules ir = IndexingRules.fromPath(new ObjectJsonPath("prop1"))
                .withFullText().build();


        final ObjectTypeParsingRules rule = ObjectTypeParsingRules.getBuilder(
                objectType, new StorageObjectType("foo", "bar"))
                .withIndexingRule(ir).build();
        
        indexObject(guid1, rule,
                "{\"prop1\":\"multiWordInSearchMethod1 multiWordInSearchMethod2\"}",
                "multiword.1", Instant.now(), null, true);
        indexObject(guid2, rule, "{\"prop1\":\"multiWordInSearchMethod2\"}",
                "multiword.2", Instant.now(), null, true);
        indexObject(guid3, rule, "{\"prop1\":\"multiWordInSearchMethod1\"}",
                "multiword.3", Instant.now(), null, true);
    }


    @Test
    public void testMultiwordSearch() throws Exception {
        GUID guid1 = new GUID("WS:11/1/2");
        GUID guid2 = new GUID("WS:11/2/2");
        GUID guid3 = new GUID("WS:11/3/2");
        prepareTestMultiwordSearch(guid1, guid2, guid3);

        List<String> empty = new ArrayList<>();

        final Builder filter = MatchFilter.getBuilder();
        List<kbasesearchengine.search.SortingRule> sorting = null;
        final AccessFilter accessFilter = AccessFilter.create().withAdmin(true);

        filter.withNullableFullTextInAll("multiWordInSearchMethod1 multiWordInSearchMethod2");
        FoundHits hits1 = indexStorage.searchObjects(empty, filter.build(), sorting, accessFilter,
                null, null);

        filter.withNullableFullTextInAll("multiWordInSearchMethod1");
        FoundHits hits2 = indexStorage.searchObjects(empty, filter.build(), sorting, accessFilter,
                null, null);


        filter.withNullableFullTextInAll("multiWordInSearchMethod2");
        FoundHits hits3 = indexStorage.searchObjects(empty, filter.build(), sorting, accessFilter,
                null, null);

        assertThat("did not find object1", hits1.guids, is(set(guid1)));
        assertThat("did not find object1 and object3", hits2.guids, is(set(guid1,guid3)));
        assertThat("did not find object1 and object2", hits3.guids, is(set(guid1, guid2)));

    }

    private void prepareTestLookupInKey(GUID guid1, GUID guid2, GUID guid3) throws Exception {
        final SearchObjectType objType = new SearchObjectType("SimpleNumber", 1 );
        final IndexingRules ir1 = IndexingRules.fromPath(new ObjectJsonPath("num1"))
                .withKeywordType("integer").build();
        final IndexingRules ir2 = IndexingRules.fromPath(new ObjectJsonPath("num2"))
                .withKeywordType("integer").build();

        final ObjectTypeParsingRules rule = ObjectTypeParsingRules.getBuilder(
                objType, new StorageObjectType("foo", "bar"))
                .withIndexingRule(ir1)
                .withIndexingRule(ir2).build();

        indexObject(guid1, rule, "{\"num1\": 123, \"num2\": 123}",
                "number.1", Instant.now(), null, false);
        indexObject(guid2, rule, "{\"num1\": 1234, \"num2\": 1234}",
                "number.2", Instant.now(), null, false);
        indexObject(guid3, rule, "{\"num1\": 1236, \"num2\": 1236}",
                "number.3", Instant.now(), null, false);
    }
    @Test
    public void testLookupInKey() throws Exception {
        GUID guid1 = new GUID("WS:12/1/2");
        GUID guid2 = new GUID("WS:12/2/2");
        GUID guid3 = new GUID("WS:12/3/2");
        prepareTestLookupInKey(guid1, guid2, guid3);
        List<String> emtpy = new ArrayList<>();


        List<kbasesearchengine.search.SortingRule> sorting = null;
        AccessFilter accessFilter = AccessFilter.create().withAdmin(true);

        //key, value pair lookup
        MatchFilter filter0 = MatchFilter.getBuilder().withLookupInKey(
                "num1", "123").build();
        FoundHits hits0 = indexStorage.searchObjects(emtpy, filter0,sorting, accessFilter
                , null, null);
        assertThat("did not find object1 using LookupInKey with value", hits0.guids, is(set(guid1)));


        //key, range lookup
        MatchValue range1 = new MatchValue(100, 200);
        MatchValue range2 = new MatchValue(1000, 2000);
        MatchValue range3 = new MatchValue(100, 1234);

        MatchFilter filter1 = MatchFilter.getBuilder().withLookupInKey("num1", range1).build();
        MatchFilter filter2 = MatchFilter.getBuilder().withLookupInKey("num2", range2).build();
        MatchFilter filter3 = MatchFilter.getBuilder().withLookupInKey("num1", range3).build();

        FoundHits hits1 = indexStorage.searchObjects(emtpy, filter1,sorting,
                accessFilter, null, null);
        FoundHits hits2 = indexStorage.searchObjects(emtpy, filter2,sorting,
                accessFilter, null, null);
        FoundHits hits3 = indexStorage.searchObjects(emtpy, filter3,sorting,
                accessFilter, null, null);

        assertThat("did not find object1 using LookupInKey with range", hits1.guids, is(set(guid1)));
        assertThat("did not find object2 and object3 using LookupInKey with range",
                hits2.guids, is(set(guid2, guid3)));
        assertThat("did not find object1 and object3 using LookupInKey with range",
                hits3.guids, is(set(guid1, guid2)));

        //conflicting filters should return nothing
        final MatchFilter filter4 = MatchFilter.getBuilder().withLookupInKey("num1", range1)
                .withLookupInKey("num2", range2).build();
        FoundHits hits4 = indexStorage.searchObjects(emtpy, filter4, sorting, accessFilter,
                null, null);

        assertThat("conflicting ranges should produce 0 results", hits4.guids.isEmpty(), is(true));


        // overlapping filters should return intersection
        final MatchFilter filter5 = MatchFilter.getBuilder().withLookupInKey("num1", range3)
            .withLookupInKey("num2", range2).build();
        FoundHits hits5 = indexStorage.searchObjects(emtpy, filter5,sorting, accessFilter
                , null, null);

        assertThat("overlapping ranges did not return intersection", hits5.guids, is(set(guid2)));
    }

    @Test
    public void addHighlighting() throws Exception {
        GUID guid1 = new GUID("WS:11/1/2");
        GUID guid2 = new GUID("WS:11/2/2");
        GUID guid3 = new GUID("WS:11/3/2");
        prepareTestMultiwordSearch(guid1, guid2, guid3);

        PostProcessing pp = new PostProcessing();
        List<String> empty = new ArrayList<>();
        

        List<kbasesearchengine.search.SortingRule> sorting = null;
        AccessFilter accessFilter = AccessFilter.create().withAdmin(true);

        //searchObjects
        final Builder filter = MatchFilter.getBuilder();
        filter.withNullableFullTextInAll("multiWordInSearchMethod1 multiWordInSearchMethod2");

        //tests that turning off highlight works
        //highlight turned off would give null objects unless objectData/objectInfo/objectKey is set to true
        pp.objectData = true;
        FoundHits hits0 = indexStorage.searchObjects(empty, filter.build(),sorting, accessFilter, null, pp);

        assertThat("Incorrect highlighting", hits0.objects.get(0).getHighlight(), is(Collections.emptyMap()) );

        //turn on highlight
        pp.objectData = false;
        pp.objectHighlight = true;
        FoundHits hits = indexStorage.searchObjects(empty, filter.build(),sorting, accessFilter, null, pp);
        Map<String, List<String>> hitRes = hits.objects.get(0).getHighlight();

        Map<String, List<String>> result1 = new HashMap<>();
        result1.put("prop1", Arrays.asList("<em>multiWordInSearchMethod1</em> <em>multiWordInSearchMethod2</em>"));
        assertThat("Incorrect highlighting", hitRes, is(result1) );

        //searchIds is a wrapper around queryHits and does not return object data and so will not be highlighted
        //searchTypes returns the number of items per type that. No highlight neccesary.

        //getObjectsByIds -- if you ever want to get the guids back highlighted...
        Set<GUID> guids = new HashSet<>();
        guids.add(guid1);
        List<ObjectData> objIdsData = indexStorage.getObjectsByIds(guids, pp);
        Map<String, List<String>> result2 = new HashMap<>();
        result2.put("guid", Arrays.asList("<em>WS:11/1/2</em>"));
        for(ObjectData obj: objIdsData) {
            Map<String, List<String>> res = obj.getHighlight();
            assertThat("Incorrect highlighting", res, is(result2));
        }
    }
    
    @Test
    public void noSubObjects() throws Exception {
        indexStorage.indexObjects(
                ObjectTypeParsingRules.getBuilder(
                        new SearchObjectType("subtype", 1),
                        new StorageObjectType("WS", "parenttype"))
                        .toSubObjectRule(
                                "subtype", new ObjectJsonPath("/foo"), new ObjectJsonPath("/bar"))
                        .build(),
                SourceData.getBuilder(new UObject(new HashMap<>()), "objname", "someguy").build(),
                Instant.ofEpochMilli(10000L),
                "{}",
                new GUID("WS:1/2/3"),
                Collections.emptyMap(), // this should not generally happen for subobjects,
                                        // but can in pathological cases
                true);
        
        final FoundHits res = indexStorage.searchObjects(
                Collections.emptyList(),
                MatchFilter.getBuilder().build(),
                null, // sorting rule
                AccessFilter.create().withPublic(true),
                null, // pagination
                null); // post processing
        
        assertThat("no data stored", res.guids.isEmpty(), is(true));
        assertThat("no data stored", res.objects, is((Set<ObjectData>) null));
        assertThat("no data stored", res.total, is(0));
    }

    @Test
    public void strictMappingFail() throws Exception {
        ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
                .getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME))
                .setLevel(Level.ALL);

        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        final Appender mockAppender = mock(Appender.class);
        when(mockAppender.getName()).thenReturn("MOCK");
        root.addAppender(mockAppender);

        final Map<String, Object> data = new HashMap<>();
        data.put("source", "a long string");
        data.put("title", "a title");
        data.put("newField", "new field value");  // that does not exist in map


        indexStorage.indexObjects(
                ObjectTypeParsingRules.getBuilder(
                        new SearchObjectType("Narrative", 1),
                        new StorageObjectType("WS", "Narrative"))
                        .withIndexingRule(IndexingRules.fromPath(new ObjectJsonPath("source"))
                                .build())
                        .withIndexingRule(IndexingRules.fromPath(new ObjectJsonPath("title"))
                                .build())
                        .build(),
                SourceData.getBuilder(new UObject(new HashMap<>()), "objname1", "creator1")
                        .build(),
                Instant.ofEpochMilli(10000),
                null,
                new GUID("WS:1/1/1"),
                ImmutableMap.of(new GUID("WS:1/1/1"), new ParsedObject(
                        new ObjectMapper().writeValueAsString(data),
                        data.entrySet().stream().collect(Collectors.toMap(
                                e -> e.getKey(), e -> Arrays.asList(e.getValue()))))),
                false);

        // verify error log was written
        verify(mockAppender).doAppend(argThat(new ArgumentMatcher() {
            @Override
            public boolean matches(final Object argument) {
                String msg = ((LoggingEvent)argument).getFormattedMessage();
                return msg.contains("status=400") &&
                        msg.contains("type=strict_dynamic_mapping_exception") &&
                        msg.contains("reason=mapping set to strict, dynamic introduction of " +
                                "[newField] within [key] is not allowed");
            }
        }));
    }


    @Test
    public void strictMappingPass() throws Exception {
        ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
                .getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME))
                .setLevel(Level.ALL);

        // input data fits mapping
        final Map<String, Object> data = new HashMap<>();
        data.put("source", "a long string");
        data.put("title", "a title");

        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        final Appender mockAppender = mock(Appender.class);
        when(mockAppender.getName()).thenReturn("MOCK");
        root.addAppender(mockAppender);

        indexStorage.indexObjects(
                ObjectTypeParsingRules.getBuilder(
                        new SearchObjectType("Narrative", 1),
                        new StorageObjectType("WS", "Narrative"))
                        .withIndexingRule(IndexingRules.fromPath(new ObjectJsonPath("source"))
                                .build())
                        .withIndexingRule(IndexingRules.fromPath(new ObjectJsonPath("title"))
                                .build())
                        .build(),
                SourceData.getBuilder(new UObject(new HashMap<>()), "objname1", "creator1")
                        .build(),
                Instant.ofEpochMilli(10000),
                null,
                new GUID("WS:1/1/1"),
                ImmutableMap.of(new GUID("WS:1/1/1"), new ParsedObject(
                        new ObjectMapper().writeValueAsString(data),
                        data.entrySet().stream().collect(Collectors.toMap(
                                e -> e.getKey(), e -> Arrays.asList(e.getValue()))))),
                false);

        // verify info log was written
        verify(mockAppender).doAppend(argThat(new ArgumentMatcher() {
            @Override
            public boolean matches(final Object argument) {
                String msg = ((LoggingEvent)argument).getFormattedMessage();
                return msg.contains("Indexed 1 item(s).");
            }
        }));
    }

    @Test
    public void testDeleteObjectWithSubobject() throws Exception {
        // regular object
        indexStorage.indexObjects(
                ObjectTypeParsingRules.getBuilder(
                        new SearchObjectType("DeleteObjectWithSubObject", 1),
                        new StorageObjectType("foo", "bar"))
                        .withIndexingRule(IndexingRules.fromPath(new ObjectJsonPath("whee"))
                                .build())
                        .build(),
                SourceData.getBuilder(new UObject(new HashMap<>()), "objname", "creator").build(),
                Instant.ofEpochMilli(10000),
                null,
                new GUID("WS:2000/1/1"),
                ImmutableMap.of(new GUID("WS:2000/1/1"), new ParsedObject(
                        "{\"whee\": \"imaprettypony\"}",
                        ImmutableMap.of("whee", Arrays.asList("imaprettypony")))),
                false);

        // sub object
        indexStorage.indexObjects(
                ObjectTypeParsingRules.getBuilder(
                        new SearchObjectType("DeleteObjectWithSubObject", 1),
                        new StorageObjectType("foo", "bar"))
                        .toSubObjectRule(
                                "sub", new ObjectJsonPath("subpath"), new ObjectJsonPath("id"))
                        .withIndexingRule(IndexingRules.fromPath(new ObjectJsonPath("whee"))
                                .build())
                        .build(),
                SourceData.getBuilder(new UObject(new HashMap<>()), "objname", "creator").build(),
                Instant.ofEpochMilli(10000),
                null,
                new GUID("WS:2000/1/1"),
                ImmutableMap.of(new GUID("WS:2000/1/1:sub/yay"), new ParsedObject(
                        "{\"whee\": \"imaprettypony\"}",
                        ImmutableMap.of("whee", Arrays.asList("imaprettypony")))),
                false);


        //check that both objects are found
        final Set<GUID> res = indexStorage.searchIds(
                Collections.emptyList(),
                MatchFilter.getBuilder().withNullableFullTextInAll("imaprettypony").build(),
                null,
                AccessFilter.create().withAccessGroups(2000));
        assertThat("incorrect objects found", res,
                is(set(new GUID("WS:2000/1/1"), new GUID("WS:2000/1/1:sub/yay"))));

        //delete the object
        indexStorage.deleteAllVersions(new GUID("WS:2000/1/1"));
        indexStorage.refreshIndex("*");

        final Set<GUID> res2 = indexStorage.searchIds(
                Collections.emptyList(),
                MatchFilter.getBuilder().withNullableFullTextInAll("imaprettypony").build(),
                null,
                AccessFilter.create().withAccessGroups(2000));

        assertThat("objects not deleted", res2, is(set()));
    }

    @Test
    public void testSearchObjectType() throws Exception {
        final boolean res0 = indexStorage.hasParentId("someobject", new GUID("WS:2000/1/1"));
        assertThat("parent id should not exist", res0, is(false));

        indexStorage.indexObjects(
                ObjectTypeParsingRules.getBuilder(
                        new SearchObjectType("Someobject", 1),
                        new StorageObjectType("foo", "bar"))
                        .withIndexingRule(IndexingRules.fromPath(new ObjectJsonPath("whee"))
                                .build())
                        .build(),
                SourceData.getBuilder(new UObject(new HashMap<>()), "objname", "creator").build(),
                Instant.ofEpochMilli(10000),
                null,
                new GUID("WS:2000/1/1"),
                ImmutableMap.of(new GUID("WS:2000/1/1"), new ParsedObject(
                        "{\"whee\": \"imaprettypony\"}",
                        ImmutableMap.of("whee", Arrays.asList("imaprettypony")))),
                false);

        final boolean res = indexStorage.hasParentId("someobject", new GUID("WS:2000/1/1"));
        assertThat("could not find parent id", res, is(true));

    }

}
