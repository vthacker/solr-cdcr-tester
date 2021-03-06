package org.apache.solr;


import org.apache.lucene.util.TestUtil;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.CloudSolrClient;
import org.apache.solr.client.solrj.request.UpdateRequest;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.params.CommonParams;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

public class UltimateCdcrTesterV2 {

    private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
    static String[] strings = new String[5];
    static final String[] FIELDS = new String[]{"order_no_s", "ship_addr_s"};
    private static Random r = new Random();
    private static String C1_ZK;
    private static String C2_ZK;
    private static String col1 = "cluster1";
    private static String col2 = "cluster2";
    private static final String ALL = "*:*";

    public static void main(String args[]) throws IOException, SolrServerException, Exception {
        if (args.length == 0) {
            args = new String[]{"localhost:9983", "localhost:8574", "1", "1"};
            // default indexing batch size: 1024 x 1 = 1024
            // default number of iterations: 200 x 1 = 200
        }
        C1_ZK = args[0]; //set cluster-1 zk
        C2_ZK = args[1]; //set cluster-2 zk
        if (args.length > 4) {
            col1 = args[4]; //setup col 1 name
            col2 = args[5]; //setup col 2 name
        }

        String source_zkHost = C1_ZK; //initial_source
        String target_zkHost = C2_ZK; //initial_target
        CloudSolrClient source_cli = new CloudSolrClient.Builder().withZkHost(source_zkHost).build();
        String source_col = col1;
        source_cli.setDefaultCollection(source_col);
        CloudSolrClient target_cli = new CloudSolrClient.Builder().withZkHost(target_zkHost).build();
        String target_col = col2;
        source_cli.setDefaultCollection(target_col);

        long curr_time = System.currentTimeMillis();
        System.out.println("start-time :: " + curr_time);

        ThreadLocalRandom r = ThreadLocalRandom.current();
        UpdateRequest updateRequest = null;

        // create data for payload
        loadData();

        // first level sanity check
        {
            for (int i=0; i<2; i++) { // 2 iterations, toggles
                // index one doc
                SolrInputDocument doc1 = new SolrInputDocument();
                String id1 = UUID.randomUUID().toString();
                doc1.addField("id", id1);
                doc1.addField(FIELDS[0], strings[r.nextInt(5) % 5]);
                doc1.addField(FIELDS[1], strings[r.nextInt(5) % 5]);

                SolrInputDocument doc2 = new SolrInputDocument();
                String id2 = UUID.randomUUID().toString();
                doc2.addField("id", id2);
                doc2.addField(FIELDS[0], strings[r.nextInt(5) % 5]);
                doc2.addField(FIELDS[1], strings[r.nextInt(5) % 5]);

                updateRequest = new UpdateRequest();
                updateRequest.add(index(Arrays.asList(new SolrInputDocument[]{doc1, doc2})));

                System.out.println("index: " + updateRequest);
                source_cli.request(updateRequest, source_col);
                updateRequest.commit(source_cli, source_col);
                waitForSync(source_cli, target_cli, ALL);

                // delete by id
                deleteById(source_cli, target_cli, source_col);

                // delete by query
                deleteByQuery(source_cli, target_cli, source_col);

                // toggle source and target
                {
                    source_cli.close();
                    target_cli.close();
                    if (source_zkHost.equals(C1_ZK)) {
                        // make C2_ZK primary
                        source_zkHost = C2_ZK;
                        source_col = col2;
                        target_zkHost = C1_ZK;
                        target_col = col1;
                    } else {
                        // make C1_ZK primary
                        source_zkHost = C1_ZK;
                        source_col = col1;
                        target_zkHost = C2_ZK;
                        target_col = col2;
                    }
                    System.out.println("toggle direction: " + source_col + " | " + target_col);

                    source_cli = new CloudSolrClient.Builder().withZkHost(source_zkHost).build();
                    source_cli.setDefaultCollection(source_col);
                    target_cli = new CloudSolrClient.Builder().withZkHost(target_zkHost).build();
                    source_cli.setDefaultCollection(target_col);
                }
            }
        }

        for (int j = 0; j < 200 * Integer.parseInt(args[2]); j++) {
            int action = r.nextInt(4) % 4;
            // 50% of probability of indexing, and 25%% of other two ops:
            // delete-by-id and delete-by-query

            // toggle source and target on very iteration
            {
                source_cli.close();
                target_cli.close();
                if (source_zkHost.equals(C1_ZK)) {
                    // make C2_ZK primary
                    source_zkHost = C2_ZK;
                    source_col = col2;
                    target_zkHost = C1_ZK;
                    target_col = col1;
                } else {
                    // make C1_ZK primary
                    source_zkHost = C1_ZK;
                    source_col = col1;
                    target_zkHost = C2_ZK;
                    target_col = col2;
                }
                System.out.println("toggle direction: " + source_col + " | " + target_col);

                source_cli = new CloudSolrClient.Builder().withZkHost(source_zkHost).build();
                source_cli.setDefaultCollection(source_col);
                target_cli = new CloudSolrClient.Builder().withZkHost(target_zkHost).build();
                source_cli.setDefaultCollection(target_col);
            }

            switch (action) {

                case 0:
                    // delete-by-id
                    deleteById(source_cli, target_cli, source_col);
                    break;

                case 1:
                    // delete-by-query (delete both parent and child doc)
                    deleteByQuery(source_cli, target_cli, source_col);
                    break;

                default:
                    // index
                    List<SolrInputDocument> docs = new ArrayList<>();
                    for (int i = 0; i < 1024 * Integer.parseInt(args[3]); i++) {
                        docs = index(docs);
                    }
                    updateRequest = new UpdateRequest();
                    updateRequest.add(docs);

                    System.out.println("index: " + updateRequest);
                    source_cli.request(updateRequest, source_col);
                    updateRequest.commit(source_cli, source_col);

                    docs.clear();
                    break;
            }
            Thread.sleep(500);
        }

        long end_time = System.currentTimeMillis();
        System.out.println("start-time :: " + end_time);
        System.out.println("total time spend :: " + (end_time - curr_time));

        System.exit(0);
    }

    // DBI
    private static void  deleteById(CloudSolrClient source_cli, CloudSolrClient target_cli, String source_col)
            throws Exception {
        UpdateRequest updateRequest = new UpdateRequest();
        String fieldName = FIELDS[r.nextInt(2) % 2];
        String fieldValue = strings[r.nextInt(5) % 5];
        String payload = fieldName + ":" + fieldValue;
        QueryResponse source_resp = source_cli.query(new SolrQuery(payload));
        if (source_resp.getResults().getNumFound() > 0) {
            String idToDel = source_resp.getResults().get(0).get("id").toString();
            if (idToDel.contains("parent_") || idToDel.contains("child_")) {
                //nested document, delete both parent and child to avoid linging docs
                if (idToDel.contains("parent_")) {
                    idToDel = idToDel.substring("parent_".length());
                } else {
                    idToDel = idToDel.substring("child_".length());
                }
                updateRequest.deleteById(Arrays.asList(new String[]{"parent_" + idToDel, "child_" + idToDel}));
                // update payload to verify on other cluster
                payload = "id:" + updateRequest.getDeleteById().get(0) + "OR" + updateRequest.getDeleteById().get(1);
            } else { //singlular document
                updateRequest.deleteById(idToDel);
                // update payload to verify on other cluster
                payload = "id:" + updateRequest.getDeleteById().get(0);
            }
            source_cli.request(updateRequest, source_col);
            updateRequest.commit(source_cli, source_col);

            waitForSync(source_cli, target_cli, payload);
        }

    }

    // DBQ
    private static void deleteByQuery(CloudSolrClient source_cli, CloudSolrClient target_cli, String source_col)
            throws Exception {
        UpdateRequest updateRequest = new UpdateRequest();
        String fieldName1 = FIELDS[r.nextInt(2) % 2];
        String fieldValue1 = strings[r.nextInt(5) % 5];
        String payload1 = fieldName1 + ":" + fieldValue1;
        updateRequest.deleteByQuery(payload1);
        System.out.println("deleteByQuery: " + updateRequest);

        source_cli.request(updateRequest, source_col);
        updateRequest.commit(source_cli, source_col);

        waitForSync(source_cli, target_cli, payload1);
    }

    // add-update
    private static List<SolrInputDocument> index(List<SolrInputDocument> docs) {
        SolrInputDocument document = new SolrInputDocument();
        String id = UUID.randomUUID().toString();
        document.addField("id", id);
        document.addField("member_id_i", r.nextInt(5) % 5);
        document.addField("subtotal_i", 1024 + r.nextInt(5) % 5);
        document.addField("quantity_l", Math.abs(r.nextLong() % 5));
        String order = strings[r.nextInt(5) % 5];
        String ship_addr = strings[r.nextInt(5) % 5];
        document.addField(FIELDS[0], order);
        document.addField(FIELDS[1], ship_addr);
        if (r.nextBoolean()) { // with child doc
            SolrInputDocument childdoc = new SolrInputDocument();
            childdoc.addField("id", "child_" + id);
            childdoc.addField(FIELDS[0], order);
            childdoc.addField(FIELDS[1], ship_addr);
            document.addChildDocument(childdoc);
            document.addField("id", "parent_" + id);
        } else {
            document.addField("id", id); // no child doc
        }
        docs.add(document);
        return docs;
    }

    // create sentences for data to index
    private static String createSentance(int numWords) {
        //Sentence with numWords and 3-7 letters in each word
        StringBuilder sb = new StringBuilder(numWords * 2);
        for (int i = 0; i < numWords; i++) {
            sb.append(TestUtil.randomSimpleString(r, 1, 1));
        }
        return sb.toString();
    }

    // create data for index
    private static void loadData() {
        // to index payload
        for (int i = 0; i < 5; i++) {
            strings[i] = createSentance(1);
        }
    }

    // helper function for checkpoint
    private static long checkpoint(CloudSolrClient solrClient) throws Exception {
        ModifiableSolrParams params = new ModifiableSolrParams();
        params.set(CommonParams.ACTION, "COLLECTIONCHECKPOINT");
        params.set(CommonParams.QT, "/cdcr");
        QueryResponse response = solrClient.query(params);
        return (Long) response.getResponse().get("CHECKPOINT");
    }

    // helper function to all cluster
    private static boolean clusterInSync(CloudSolrClient src, CloudSolrClient tar) throws Exception {
        //if (checkpoint(src) == checkpoint(tar)) {
        if (src.query(new SolrQuery(ALL)).getResults().getNumFound() == (tar.query(new SolrQuery(ALL)).getResults().getNumFound())) {
            return true;
        }
        //}
        return false;
    }

    // helper function to validate sync
    private static void waitForSync(CloudSolrClient source_cli, CloudSolrClient target_cli, String payload) throws Exception {
        long start = System.nanoTime();
        QueryResponse source_resp = source_cli.query(new SolrQuery(payload));
        QueryResponse target_resp = null;
        while (System.nanoTime() - start <= TimeUnit.NANOSECONDS.convert(240, TimeUnit.SECONDS)) {
            Thread.sleep(2000); // pause
            if (!clusterInSync(source_cli, target_cli)) {
                continue;
            }
            target_cli.commit();
            target_resp = target_cli.query(new SolrQuery(payload));
            if (target_resp.getResults().getNumFound() == source_resp.getResults().getNumFound()) {
                break;
            }
        }
        if (target_resp != null) {
            assert target_resp.getResults().getNumFound() == source_resp.getResults().getNumFound();
        }
    }

}
