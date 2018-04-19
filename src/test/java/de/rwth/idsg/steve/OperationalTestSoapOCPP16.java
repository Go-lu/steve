package de.rwth.idsg.steve;

import de.rwth.idsg.steve.repository.ReservationStatus;
import de.rwth.idsg.steve.repository.dto.ChargePoint;
import de.rwth.idsg.steve.repository.dto.ConnectorStatus;
import de.rwth.idsg.steve.repository.dto.Reservation;
import de.rwth.idsg.steve.repository.dto.Transaction;
import de.rwth.idsg.steve.repository.dto.TransactionDetails;
import de.rwth.idsg.steve.utils.__DatabasePreparer__;
import jooq.steve.db.tables.records.TransactionRecord;
import lombok.extern.slf4j.Slf4j;
import ocpp.cs._2015._10.AuthorizationStatus;
import ocpp.cs._2015._10.AuthorizeRequest;
import ocpp.cs._2015._10.AuthorizeResponse;
import ocpp.cs._2015._10.BootNotificationRequest;
import ocpp.cs._2015._10.BootNotificationResponse;
import ocpp.cs._2015._10.CentralSystemService;
import ocpp.cs._2015._10.ChargePointErrorCode;
import ocpp.cs._2015._10.ChargePointStatus;
import ocpp.cs._2015._10.HeartbeatRequest;
import ocpp.cs._2015._10.HeartbeatResponse;
import ocpp.cs._2015._10.MeterValue;
import ocpp.cs._2015._10.MeterValuesRequest;
import ocpp.cs._2015._10.MeterValuesResponse;
import ocpp.cs._2015._10.RegistrationStatus;
import ocpp.cs._2015._10.SampledValue;
import ocpp.cs._2015._10.StartTransactionRequest;
import ocpp.cs._2015._10.StartTransactionResponse;
import ocpp.cs._2015._10.StatusNotificationRequest;
import ocpp.cs._2015._10.StatusNotificationResponse;
import ocpp.cs._2015._10.StopTransactionRequest;
import ocpp.cs._2015._10.StopTransactionResponse;
import org.joda.time.DateTime;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static de.rwth.idsg.steve.utils.Helpers.getForOcpp16;
import static de.rwth.idsg.steve.utils.Helpers.getPath;
import static de.rwth.idsg.steve.utils.Helpers.getRandomString;

/**
 * @author Andreas Heuvels <andreas.heuvels@rwth-aachen.de>
 * @since 22.03.18
 */
@Slf4j
public class OperationalTestSoapOCPP16 {

    private static final String REGISTERED_CHARGE_BOX_ID = __DatabasePreparer__.getRegisteredChargeBoxId();
    private static final String REGISTERED_OCPP_TAG = __DatabasePreparer__.getRegisteredOcppTag();
    private static final String path = getPath();
    private static final int numConnectors = 5;
    private static Application app;

    @BeforeClass
    public static void initClass() throws Exception {
        app = new Application();
        app.start();
    }

    @AfterClass
    public static void destroyClass() throws Exception {
        app.stop();
    }

    @Before
    public void init() throws Exception {
        __DatabasePreparer__.prepare();
    }

    @After
    public void destroy() throws Exception {
        __DatabasePreparer__.cleanUp();
    }

    @Test
    public void testUnregisteredCP() {
        CentralSystemService client = getForOcpp16(path);

        BootNotificationResponse boot = client.bootNotification(
                new BootNotificationRequest()
                        .withChargePointVendor(getRandomString())
                        .withChargePointModel(getRandomString()),
                getRandomString());

        Assert.assertNotNull(boot);
        Assert.assertNotEquals(RegistrationStatus.ACCEPTED, boot.getStatus());
    }

    @Test
    public void testRegisteredCP() {
        CentralSystemService client = getForOcpp16(path);

        initStationWithBootNotification(client);

        ChargePoint.Details details = __DatabasePreparer__.getCBDetails(REGISTERED_CHARGE_BOX_ID);
        Assert.assertTrue(details.getChargeBox().getOcppProtocol().contains("ocpp1.6"));
    }

    @Test
    public void testRegisteredIdTag() {
        CentralSystemService client = getForOcpp16(path);

        AuthorizeResponse auth = client.authorize(
                new AuthorizeRequest().withIdTag(REGISTERED_OCPP_TAG),
                REGISTERED_CHARGE_BOX_ID);

        Assert.assertNotNull(auth);
        Assert.assertEquals(AuthorizationStatus.ACCEPTED, auth.getIdTagInfo().getStatus());
    }

    @Test
    public void testUnregisteredIdTag() {
        CentralSystemService client = getForOcpp16(path);

        AuthorizeResponse auth = client.authorize(
                new AuthorizeRequest().withIdTag(getRandomString()),
                REGISTERED_CHARGE_BOX_ID);

        Assert.assertNotNull(auth);
        Assert.assertEquals(AuthorizationStatus.INVALID, auth.getIdTagInfo().getStatus());
    }

    @Test
    public void testInTransactionStatusOfIdTag() {
        CentralSystemService client = getForOcpp16(path);

        StartTransactionResponse start = client.startTransaction(
                new StartTransactionRequest()
                        .withConnectorId(2)
                        .withIdTag(REGISTERED_OCPP_TAG)
                        .withTimestamp(DateTime.now())
                        .withMeterStart(0),
                REGISTERED_CHARGE_BOX_ID
        );

        Assert.assertNotNull(start);
        Assert.assertTrue(start.getTransactionId() > 0);
        Assert.assertTrue(__DatabasePreparer__.getOcppTagRecord(REGISTERED_OCPP_TAG).getInTransaction());

        StopTransactionResponse stop = client.stopTransaction(
                new StopTransactionRequest()
                        .withTransactionId(start.getTransactionId())
                        .withTimestamp(DateTime.now())
                        .withIdTag(REGISTERED_OCPP_TAG)
                        .withMeterStop(30),
                REGISTERED_CHARGE_BOX_ID
        );

        Assert.assertNotNull(stop);
        Assert.assertFalse(__DatabasePreparer__.getOcppTagRecord(REGISTERED_OCPP_TAG).getInTransaction());
    }

    @Test
    public void testStatusNotification() {
        CentralSystemService client = getForOcpp16(path);

        // -------------------------------------------------------------------------
        // init the station and verify db connector status values
        // -------------------------------------------------------------------------

        initStationWithBootNotification(client);

        // test all status enum values
        for (ChargePointStatus chargePointStatus : ChargePointStatus.values()) {
            // status for numConnectors connectors + connector 0 (main controller of CP)
            for (int i = 0; i <= numConnectors; i++) {
                StatusNotificationResponse status = client.statusNotification(
                        new StatusNotificationRequest()
                                .withErrorCode(ChargePointErrorCode.NO_ERROR)
                                .withStatus(chargePointStatus)
                                .withConnectorId(i)
                                .withTimestamp(DateTime.now()),
                        REGISTERED_CHARGE_BOX_ID
                );
                Assert.assertNotNull(status);
            }

            List<ConnectorStatus> connectorStatusList = __DatabasePreparer__.getChargePointConnectorStatus();
            for (ConnectorStatus connectorStatus : connectorStatusList) {
                Assert.assertEquals(chargePointStatus.value(), connectorStatus.getStatus());
                Assert.assertEquals(ChargePointErrorCode.NO_ERROR.value(), connectorStatus.getErrorCode());
            }
        }

        // -------------------------------------------------------------------------
        // send status for faulty connector and verify db values
        // -------------------------------------------------------------------------

        int faultyConnectorId = 1;

        StatusNotificationResponse statusConnectorError = client.statusNotification(
                new StatusNotificationRequest()
                        .withErrorCode(ChargePointErrorCode.HIGH_TEMPERATURE)
                        .withStatus(ChargePointStatus.FAULTED)
                        .withConnectorId(faultyConnectorId)
                        .withTimestamp(DateTime.now()),
                REGISTERED_CHARGE_BOX_ID
        );
        Assert.assertNotNull(statusConnectorError);


        List<ConnectorStatus> connectorStatusList = __DatabasePreparer__.getChargePointConnectorStatus();
        for (ConnectorStatus connectorStatus : connectorStatusList) {
            if (connectorStatus.getConnectorId() == faultyConnectorId) {
                Assert.assertEquals(ChargePointStatus.FAULTED.value(), connectorStatus.getStatus());
                Assert.assertEquals(ChargePointErrorCode.HIGH_TEMPERATURE.value(), connectorStatus.getErrorCode());
            } else {
                Assert.assertNotEquals(ChargePointStatus.FAULTED.value(), connectorStatus.getStatus());
                Assert.assertNotEquals(ChargePointErrorCode.HIGH_TEMPERATURE.value(), connectorStatus.getErrorCode());
            }
        }
    }

    @Test
    public void testReservation() {
        int usedConnectorID = 1;

        CentralSystemService client = getForOcpp16(path);

        // -------------------------------------------------------------------------
        // init the station and make reservation
        // -------------------------------------------------------------------------

        initStationWithBootNotification(client);
        initConnectorsWithStatusNotification(client);

        int reservationId = __DatabasePreparer__.makeReservation(usedConnectorID);

        // -------------------------------------------------------------------------
        // startTransaction (invalid reservationId)
        // -------------------------------------------------------------------------

        int nonExistingReservationId = reservationId + 17;

        StartTransactionResponse startInvalid = client.startTransaction(
                new StartTransactionRequest()
                        .withConnectorId(usedConnectorID)
                        .withIdTag(REGISTERED_OCPP_TAG)
                        .withTimestamp(DateTime.now())
                        .withMeterStart(0)
                        .withReservationId(nonExistingReservationId),
                REGISTERED_CHARGE_BOX_ID
        );
        Assert.assertNotNull(startInvalid);

        // validate that the transaction is written to db, even though reservation was invalid
        List<Transaction> transactions = __DatabasePreparer__.getTransactions();
        Assert.assertEquals(1, transactions.size());
        Assert.assertEquals(startInvalid.getTransactionId(), transactions.get(0).getId());

        // make sure that this invalid reservation had no side effects
        {
            List<Reservation> reservations = __DatabasePreparer__.getReservations();
            Assert.assertEquals(1, reservations.size());
            Reservation res = reservations.get(0);
            Assert.assertEquals(reservationId, res.getId());
            Assert.assertEquals(ReservationStatus.ACCEPTED.value(), res.getStatus());
        }

        // -------------------------------------------------------------------------
        // startTransaction (idtag and connectorid are not the ones from the reservation)
        // -------------------------------------------------------------------------

        StartTransactionResponse startWrongTag = client.startTransaction(
                new StartTransactionRequest()
                        .withConnectorId(3)
                        .withIdTag(getRandomString())
                        .withTimestamp(DateTime.now())
                        .withMeterStart(0)
                        .withReservationId(reservationId),
                REGISTERED_CHARGE_BOX_ID
        );
        Assert.assertNotNull(startWrongTag);

        {
            List<Reservation> reservations = __DatabasePreparer__.getReservations();
            Assert.assertEquals(1, reservations.size());
            Reservation res = reservations.get(0);
            Assert.assertEquals(ReservationStatus.ACCEPTED.value(), res.getStatus());
            Assert.assertNull(res.getTransactionId());
        }

        // -------------------------------------------------------------------------
        // startTransaction (valid)
        // -------------------------------------------------------------------------

        StartTransactionResponse startValidId = client.startTransaction(
                new StartTransactionRequest()
                        .withConnectorId(usedConnectorID)
                        .withIdTag(REGISTERED_OCPP_TAG)
                        .withTimestamp(DateTime.now())
                        .withMeterStart(0)
                        .withReservationId(reservationId),
                REGISTERED_CHARGE_BOX_ID
        );
        Assert.assertNotNull(startValidId);
        Integer transactionIdValid = startValidId.getTransactionId();

        {
            List<Reservation> reservations = __DatabasePreparer__.getReservations();
            Assert.assertEquals(reservations.size(), 1);
            Reservation res = reservations.get(0);
            Assert.assertEquals(ReservationStatus.USED.value(), res.getStatus());
            Assert.assertEquals(transactionIdValid, res.getTransactionId());
        }

        // -------------------------------------------------------------------------
        // startTransaction (valid again)
        // -------------------------------------------------------------------------

        StartTransactionResponse startValidIdUsedTwice = client.startTransaction(
                new StartTransactionRequest()
                        .withConnectorId(usedConnectorID)
                        .withIdTag(REGISTERED_OCPP_TAG)
                        .withTimestamp(DateTime.now())
                        .withMeterStart(0)
                        .withReservationId(reservationId),
                REGISTERED_CHARGE_BOX_ID
        );
        Assert.assertNotNull(startValidIdUsedTwice);

        {
            List<Reservation> reservations = __DatabasePreparer__.getReservations();
            Assert.assertEquals(reservations.size(), 1);
            Reservation res = reservations.get(0);
            Assert.assertEquals(ReservationStatus.USED.value(), res.getStatus());
            Assert.assertEquals(transactionIdValid, res.getTransactionId());
        }
    }

    @Test
    public void testWithMeterValuesAndTransactionData() {
        testBody(getMeterValues(), getTransactionData());
    }

    @Test
    public void testWithMeterValues() {
        testBody(getMeterValues(), null);
    }

    @Test
    public void testWithTransactionData() {
        testBody(null, getTransactionData());
    }

    @Test
    public void testWithoutMeterValuesAndTransactionData() {
        testBody(null, null);
    }

    private void testBody(List<MeterValue> meterValues, List<MeterValue> transactionData) {
        final int usedConnectorID = 1;

        CentralSystemService client = getForOcpp16(path);

        initStationWithBootNotification(client);
        initConnectorsWithStatusNotification(client);

        // heartbeat
        HeartbeatResponse heartbeat = client.heartbeat(
                new HeartbeatRequest(),
                REGISTERED_CHARGE_BOX_ID
        );
        Assert.assertNotNull(heartbeat);

        // Auth
        AuthorizeResponse auth = client.authorize(
                new AuthorizeRequest().withIdTag(REGISTERED_OCPP_TAG),
                REGISTERED_CHARGE_BOX_ID
        );
        // Simple request, not much done here
        Assert.assertNotNull(auth);
        Assert.assertEquals(AuthorizationStatus.ACCEPTED, auth.getIdTagInfo().getStatus());


        // startTransaction
        DateTime startTimeStamp = DateTime.now();
        StartTransactionResponse start = client.startTransaction(
                new StartTransactionRequest()
                        .withConnectorId(usedConnectorID)
                        .withIdTag(REGISTERED_OCPP_TAG)
                        .withTimestamp(startTimeStamp)
                        .withMeterStart(0),
                REGISTERED_CHARGE_BOX_ID
        );
        Assert.assertNotNull(start);

        int transactionID = start.getTransactionId();

        List<TransactionRecord> allTransactions = __DatabasePreparer__.getTransactionRecords();
        Assert.assertEquals(1, allTransactions.size());

        {
            TransactionRecord t = allTransactions.get(0);
            Assert.assertEquals(startTimeStamp, t.getStartTimestamp());
            Assert.assertEquals(0, Integer.parseInt(t.getStartValue()));

            Assert.assertNull(t.getStopTimestamp());
            Assert.assertNull(t.getStopReason());
            Assert.assertNull(t.getStopValue());
        }

        // status
        StatusNotificationResponse statusStart = client.statusNotification(
                new StatusNotificationRequest()
                        .withStatus(ChargePointStatus.CHARGING)
                        .withErrorCode(ChargePointErrorCode.NO_ERROR)
                        .withConnectorId(0)
                        .withTimestamp(DateTime.now()),
                REGISTERED_CHARGE_BOX_ID

        );
        Assert.assertNotNull(statusStart);

        // send meterValues
        if (meterValues != null) {
            MeterValuesResponse meter = client.meterValues(
                    new MeterValuesRequest()
                            .withConnectorId(usedConnectorID)
                            .withTransactionId(transactionID)
                            .withMeterValue(meterValues),
                    REGISTERED_CHARGE_BOX_ID
            );
            Assert.assertNotNull(meter);
            checkMeterValues(meterValues, transactionID);
        }

        // stopTransaction
        DateTime stopTimeStamp = DateTime.now();
        int stopValue = 30;
        StopTransactionResponse stop = client.stopTransaction(
                new StopTransactionRequest()
                        .withTransactionId(transactionID)
                        .withTransactionData(transactionData)
                        .withTimestamp(stopTimeStamp)
                        .withIdTag(REGISTERED_OCPP_TAG)
                        .withMeterStop(stopValue),
                REGISTERED_CHARGE_BOX_ID
        );

        {
            Assert.assertNotNull(stop);
            List<TransactionRecord> transactionsStop = __DatabasePreparer__.getTransactionRecords();
            Assert.assertEquals(1, transactionsStop.size());
            TransactionRecord t = transactionsStop.get(0);
            Assert.assertEquals(stopTimeStamp, t.getStopTimestamp());
            Assert.assertEquals(stopValue, Integer.parseInt(t.getStopValue()));

            if (transactionData != null) {
                checkMeterValues(transactionData, transactionID);
            }
        }

        // status
        StatusNotificationResponse statusStop = client.statusNotification(
                new StatusNotificationRequest()
                        .withStatus(ChargePointStatus.AVAILABLE)
                        .withErrorCode(ChargePointErrorCode.NO_ERROR)
                        .withConnectorId(usedConnectorID)
                        .withTimestamp(DateTime.now()),
                REGISTERED_CHARGE_BOX_ID
        );
        Assert.assertNotNull(statusStop);
    }

    private void initStationWithBootNotification(CentralSystemService client) {
        BootNotificationResponse boot = client.bootNotification(
                new BootNotificationRequest()
                        .withChargePointVendor(getRandomString())
                        .withChargePointModel(getRandomString()),
                REGISTERED_CHARGE_BOX_ID);
        Assert.assertNotNull(boot);
        Assert.assertEquals(RegistrationStatus.ACCEPTED, boot.getStatus());
    }

    private void initConnectorsWithStatusNotification(CentralSystemService client) {
        for (int i = 0; i <= numConnectors; i++) {
            StatusNotificationResponse statusBoot = client.statusNotification(
                    new StatusNotificationRequest()
                            .withErrorCode(ChargePointErrorCode.NO_ERROR)
                            .withStatus(ChargePointStatus.AVAILABLE)
                            .withConnectorId(i)
                            .withTimestamp(DateTime.now()),
                    REGISTERED_CHARGE_BOX_ID
            );
            Assert.assertNotNull(statusBoot);
        }
    }

    private void checkMeterValues(List<MeterValue> meterValues, int transactionPk) {
        TransactionDetails details = __DatabasePreparer__.getDetails(transactionPk);

        // iterate over all created meter values
        for (MeterValue meterValue : meterValues) {
            List<SampledValue> sampledValues = meterValue.getSampledValue();
            Assert.assertFalse(sampledValues.isEmpty());
            boolean thisValueFound = false;
            // and check, if it can be found in the DB
            for (TransactionDetails.MeterValues values : details.getValues()) {
                if (values.getValue().equals(sampledValues.get(0).getValue())) {
                    thisValueFound = true;
                    break;
                }
            }
            Assert.assertTrue(thisValueFound);
        }
    }

    private List<MeterValue> getTransactionData() {
        return Arrays.asList(
                createMeterValue("0.0"),
                createMeterValue("10.0"),
                createMeterValue("20.0"),
                createMeterValue("30.0")
        );
    }

    private List<MeterValue> getMeterValues() {
        return Arrays.asList(
                createMeterValue("3.0"),
                createMeterValue("13.0"),
                createMeterValue("23.0")
        );
    }

    private static MeterValue createMeterValue(String val) {
        return new MeterValue().withTimestamp(DateTime.now())
                               .withSampledValue(new SampledValue().withValue(val));
    }
}