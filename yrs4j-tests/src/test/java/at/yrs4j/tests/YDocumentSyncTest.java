package at.yrs4j.tests;

import at.yrs4j.wrapper.interfaces.ValueType;
import at.yrs4j.wrapper.interfaces.YArray;
import at.yrs4j.wrapper.interfaces.YDoc;
import at.yrs4j.wrapper.interfaces.YInput;
import at.yrs4j.wrapper.interfaces.YMap;
import at.yrs4j.wrapper.interfaces.YOutput;
import at.yrs4j.wrapper.interfaces.YText;
import at.yrs4j.wrapper.interfaces.YTransaction;
import at.yrs4j.wrapper.interfaces.YXmlElement;
import at.yrs4j.wrapper.interfaces.YXmlText;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class YDocumentSyncTest extends TestsCommon {

    @Test
    void stateDiffV1ReplicatesCommonSharedTypesIntoEmptyDocument() {
        YDoc source = createYDocWithId(1);
        YDoc target = createYDocWithId(2);

        YText sourceText = YText.createFromDoc(source, "text");
        YMap sourceMap = YMap.createWithDocAndName(source, "map");
        YArray sourceArray = YArray.createWithDocAndName(source, "array");
        YXmlElement sourceXml = YXmlElement.createWithDocAndName(source, "xml");

        YTransaction sourceTxn = source.writeTransaction();
        sourceText.insert(sourceTxn, 0, "hello world", null);

        sourceMap.insert(sourceTxn, "title", YInput.createString("sync"));
        sourceMap.insert(sourceTxn, "count", YInput.createLong(3));

        sourceArray.insertRange(sourceTxn, 0, new YInput[]{
                YInput.createString("first"),
                YInput.createLong(2),
                YInput.createBool(true)
        });

        YXmlElement paragraph = sourceXml.insertElem(sourceTxn, 0, "p");
        paragraph.insertAttr(sourceTxn, "class", "lead");
        YXmlText paragraphText = paragraph.insertText(sourceTxn, 0);
        paragraphText.insert(sourceTxn, 0, "hello xml", null);
        sourceTxn.commit();

        applyV1(target, diffV1(source, target));

        YText targetText = YText.createFromDoc(target, "text");
        YMap targetMap = YMap.createWithDocAndName(target, "map");
        YArray targetArray = YArray.createWithDocAndName(target, "array");
        YXmlElement targetXml = YXmlElement.createWithDocAndName(target, "xml");

        YTransaction targetTxn = target.readTransaction();
        assertEquals("hello world", targetText.string(targetTxn));

        assertEquals(2, targetMap.len(targetTxn));
        assertEquals("sync", targetMap.get(targetTxn, "title").readString());
        assertEquals(3, targetMap.get(targetTxn, "count").readLong());

        assertEquals(3, targetArray.len());
        assertEquals("first", targetArray.get(targetTxn, 0).readString());
        assertEquals(2, targetArray.get(targetTxn, 1).readLong());
        assertTrue(targetArray.get(targetTxn, 2).readBool());

        assertEquals(1, targetXml.childLen(targetTxn));
        YXmlElement targetParagraph = targetXml.get(targetTxn, 0).readYXmlElement();
        assertEquals("p", targetParagraph.tag());
        assertEquals("lead", targetParagraph.getAttr(targetTxn, "class"));
        assertEquals("<p class=\"lead\">hello xml</p>", targetParagraph.string(targetTxn));
        targetTxn.commit();
    }

    @Test
    void stateVectorsAllowIncrementalV1SyncAfterInitialReplication() {
        YDoc source = createYDocWithId(11);
        YDoc target = createYDocWithId(12);
        YText sourceText = YText.createFromDoc(source, "text");
        YMap sourceMap = YMap.createWithDocAndName(source, "meta");

        YTransaction firstTxn = source.writeTransaction();
        sourceText.insert(firstTxn, 0, "alpha", null);
        firstTxn.commit();

        applyV1(target, diffV1(source, target));

        YTransaction secondTxn = source.writeTransaction();
        sourceText.insert(secondTxn, 5, " beta", null);
        sourceMap.insert(secondTxn, "version", YInput.createLong(2));
        secondTxn.commit();

        byte[] incrementalUpdate = diffV1(source, target);
        assertTrue(incrementalUpdate.length > 0);
        applyV1(target, incrementalUpdate);

        YText targetText = YText.createFromDoc(target, "text");
        YMap targetMap = YMap.createWithDocAndName(target, "meta");

        YTransaction targetTxn = target.readTransaction();
        assertEquals("alpha beta", targetText.string(targetTxn));
        assertEquals(2, targetMap.get(targetTxn, "version").readLong());
        assertArrayEquals(stateVector(source), stateVector(target));
        targetTxn.commit();
    }

    @Test
    void applyingTheSameV1UpdateTwiceIsIdempotent() {
        YDoc source = createYDocWithId(21);
        YDoc target = createYDocWithId(22);
        YMap sourceMap = YMap.createWithDocAndName(source, "map");

        YTransaction sourceTxn = source.writeTransaction();
        sourceMap.insert(sourceTxn, "enabled", YInput.createBool(true));
        sourceMap.insert(sourceTxn, "name", YInput.createString("project"));
        sourceTxn.commit();

        byte[] update = diffV1(source, target);
        applyV1(target, update);
        applyV1(target, update);

        YMap targetMap = YMap.createWithDocAndName(target, "map");

        YTransaction targetTxn = target.readTransaction();
        assertEquals(2, targetMap.len(targetTxn));
        assertTrue(targetMap.get(targetTxn, "enabled").readBool());
        assertEquals("project", targetMap.get(targetTxn, "name").readString());
        assertArrayEquals(stateVector(source), stateVector(target));
        targetTxn.commit();
    }

    @Test
    void stateDiffV2ReplicatesAndIncrementallyUpdatesText() {
        YDoc source = createYDocWithId(31);
        YDoc target = createYDocWithId(32);
        YText sourceText = YText.createFromDoc(source, "text");

        YTransaction firstTxn = source.writeTransaction();
        sourceText.insert(firstTxn, 0, "one", null);
        firstTxn.commit();

        applyV2(target, diffV2(source, target));

        YTransaction secondTxn = source.writeTransaction();
        sourceText.insert(secondTxn, 3, " two", null);
        sourceText.removeRange(secondTxn, 0, 4);
        secondTxn.commit();

        applyV2(target, diffV2(source, target));

        YText targetText = YText.createFromDoc(target, "text");

        YTransaction targetTxn = target.readTransaction();
        assertEquals("two", targetText.string(targetTxn));
        assertArrayEquals(stateVector(source), stateVector(target));
        targetTxn.commit();
    }

    @Test
    void bidirectionalV1ExchangeConvergesForIndependentClientUpdates() {
        YDoc left = createYDocWithId(41);
        YDoc right = createYDocWithId(42);
        YMap leftMap = YMap.createWithDocAndName(left, "map");
        YMap rightMap = YMap.createWithDocAndName(right, "map");

        YTransaction leftTxn = left.writeTransaction();
        leftMap.insert(leftTxn, "left", YInput.createString("L"));
        leftTxn.commit();

        YTransaction rightTxn = right.writeTransaction();
        rightMap.insert(rightTxn, "right", YInput.createString("R"));
        rightTxn.commit();

        byte[] leftUpdate = diffV1(left, right);
        byte[] rightUpdate = diffV1(right, left);

        applyV1(left, rightUpdate);
        applyV1(right, leftUpdate);

        YTransaction finalLeftTxn = left.readTransaction();
        YTransaction finalRightTxn = right.readTransaction();
        assertConvergedMap(finalLeftTxn, leftMap);
        assertConvergedMap(finalRightTxn, rightMap);
        assertArrayEquals(stateVector(left), stateVector(right));
        finalLeftTxn.commit();
        finalRightTxn.commit();
    }

    @Test
    void nestedSharedTypesSurviveDocumentUpdateRoundTrip() {
        YDoc source = createYDocWithId(51);
        YDoc target = createYDocWithId(52);
        YMap sourceMap = YMap.createWithDocAndName(source, "map");

        YTransaction sourceTxn = source.writeTransaction();
        sourceMap.insert(sourceTxn, "items", YInput.createYArray(new YInput[]{
                YInput.createString("nested"),
                YInput.createLong(99)
        }));
        sourceMap.insert(sourceTxn, "body", YInput.createYText("embedded text"));
        sourceTxn.commit();

        applyV1(target, diffV1(source, target));

        YMap targetMap = YMap.createWithDocAndName(target, "map");

        YTransaction targetTxn = target.readTransaction();
        YOutput itemsOutput = targetMap.get(targetTxn, "items");
        assertEquals(ValueType.Y_ARRAY, itemsOutput.getTagValueType());
        YArray items = itemsOutput.readYArray();
        assertEquals(2, items.len());
        assertEquals("nested", items.get(targetTxn, 0).readString());
        assertEquals(99, items.get(targetTxn, 1).readLong());

        YOutput bodyOutput = targetMap.get(targetTxn, "body");
        assertEquals(ValueType.Y_TEXT, bodyOutput.getTagValueType());
        assertEquals("embedded text", bodyOutput.readYText().string(targetTxn));
        targetTxn.commit();
    }

    private byte[] diffV1(YDoc source, YDoc target) {
        YTransaction targetTxn = target.readTransaction();
        byte[] targetState = targetTxn.stateVectorV1();
        targetTxn.commit();

        YTransaction sourceTxn = source.readTransaction();
        byte[] update = sourceTxn.stateDiffV1(targetState);
        sourceTxn.commit();
        return update;
    }

    private byte[] diffV2(YDoc source, YDoc target) {
        YTransaction targetTxn = target.readTransaction();
        byte[] targetState = targetTxn.stateVectorV1();
        targetTxn.commit();

        YTransaction sourceTxn = source.readTransaction();
        byte[] update = sourceTxn.stateDiffV2(targetState);
        sourceTxn.commit();
        return update;
    }

    private void applyV1(YDoc target, byte[] update) {
        YTransaction targetTxn = target.writeTransaction();
        assertEquals(0, targetTxn.apply(update));
        targetTxn.commit();
    }

    private void applyV2(YDoc target, byte[] update) {
        YTransaction targetTxn = target.writeTransaction();
        assertEquals(0, targetTxn.applyV2(update));
        targetTxn.commit();
    }

    private byte[] stateVector(YDoc doc) {
        YTransaction txn = doc.readTransaction();
        byte[] stateVector = txn.stateVectorV1();
        txn.commit();
        return stateVector;
    }

    private void assertConvergedMap(YTransaction txn, YMap map) {
        assertEquals(2, map.len(txn));
        assertEquals("L", map.get(txn, "left").readString());
        assertEquals("R", map.get(txn, "right").readString());
    }
}
