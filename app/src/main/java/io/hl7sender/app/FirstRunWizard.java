package io.hl7sender.app;

import io.hl7sender.core.mllp.MllpClient;
import io.hl7sender.core.queue.DeliveryEngine;
import io.hl7sender.core.queue.DestinationConfig;
import io.hl7sender.core.queue.DestinationPresets;
import io.hl7sender.core.queue.DestinationProfiles;
import io.hl7sender.core.queue.EnqueueResult;
import io.hl7sender.core.queue.MessageStatus;
import io.hl7sender.core.queue.QueueException;
import io.hl7sender.core.queue.QueuedMessage;
import io.hl7sender.core.samples.SampleMessages;
import io.hl7sender.core.send.AckMode;
import io.hl7sender.core.send.SendOptions;
import io.hl7sender.core.tls.TlsOptions;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntUnaryOperator;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/**
 * Welcome wizard for a new installation: create a destination (or use the built-in test listener), test
 * the connection, and send a sample message through the queue.
 */
final class FirstRunWizard extends Dialog<DestinationConfig> {

    static final ButtonType BACK = new ButtonType(Messages.get("wizard.back"), ButtonBar.ButtonData.BACK_PREVIOUS);
    static final ButtonType NEXT = new ButtonType(Messages.get("wizard.next"), ButtonBar.ButtonData.NEXT_FORWARD);
    static final ButtonType FINISH = new ButtonType(Messages.get("wizard.finish"), ButtonBar.ButtonData.FINISH);

    /** Preset choice, including "custom". */
    private record Choice(String label, DestinationConfig template) {
        @Override
        public String toString() {
            return label;
        }
    }

    private final AppContext context;
    private final DeliveryEngine engine;
    private final IntUnaryOperator startTestListener;
    private final List<Node> steps = new ArrayList<>();
    private int step;
    private DestinationConfig destination;

    private final RadioButton useListener = new RadioButton(Messages.get("wizard.useListener"));
    private final RadioButton useReceiver = new RadioButton(Messages.get("wizard.useReceiver"));
    private final ComboBox<Choice> preset = new ComboBox<>();
    private final TextField name = new TextField();
    private final TextField host = new TextField();
    private final TextField port = Fields.integer("wizardPortField", 2575, 5, 5);
    private final CheckBox waitForAck = new CheckBox(Messages.get("wizard.waitForAck"));
    private final Label target = new Label();
    private final Label testResult = new Label();
    private final ComboBox<SampleMessages.Sample> sample = new ComboBox<>();
    private final Label sendResult = new Label();
    private final Label error = new Label();

    /**
     * @param startTestListener starts the built-in listener on the given port and returns the port it uses, or
     *                          -1 if it could not start
     */
    FirstRunWizard(Window owner, AppContext context, IntUnaryOperator startTestListener) {
        this.context = context;
        this.engine = context.engine().orElseThrow();
        this.startTestListener = startTestListener;
        initOwner(owner);
        setTitle(Messages.get("wizard.title"));
        steps.add(destinationStep());
        steps.add(testStep());
        steps.add(sendStep());
        error.setId("wizardError");
        error.getStyleClass().add("issue-error");
        error.setWrapText(true);

        getDialogPane().getButtonTypes().addAll(BACK, NEXT, FINISH, ButtonType.CANCEL);
        getDialogPane().setPrefWidth(620);
        getDialogPane().getStylesheets().add(Styles.stylesheet());
        button(BACK).setId("wizardBackButton");
        button(NEXT).setId("wizardNextButton");
        button(FINISH).setId("wizardFinishButton");
        button(ButtonType.CANCEL).setId("wizardCancelButton");
        button(BACK).addEventFilter(ActionEvent.ACTION, e -> {
            e.consume();
            show(step - 1);
        });
        button(NEXT).addEventFilter(ActionEvent.ACTION, e -> {
            e.consume();
            if (step == 0 && !createDestination()) {
                return;
            }
            show(step + 1);
        });
        setResultConverter(b -> destination);
        show(0);
    }

    private Button button(ButtonType type) {
        return (Button) getDialogPane().lookupButton(type);
    }

    private void show(int index) {
        step = Math.max(0, Math.min(steps.size() - 1, index));
        setHeaderText(Messages.get("wizard.step" + (step + 1)));
        getDialogPane().setContent(new VBox(10, steps.get(step), error));
        error.setText("");
        button(BACK).setDisable(step == 0 || step == 1 && destination != null);
        button(NEXT).setVisible(step < steps.size() - 1);
        button(NEXT).setManaged(step < steps.size() - 1);
        button(FINISH).setVisible(step == steps.size() - 1);
        button(FINISH).setManaged(step == steps.size() - 1);
        if (step == 1 && destination != null) {
            target.setText(Messages.get("wizard.target", destination.name(), destination.displayAddress()));
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Step 1: destination

    private Node destinationStep() {
        ToggleGroup group = new ToggleGroup();
        useListener.setToggleGroup(group);
        useListener.setId("wizardUseListener");
        useReceiver.setToggleGroup(group);
        useReceiver.setId("wizardUseReceiver");
        useListener.setSelected(true);

        List<Choice> choices = new ArrayList<>();
        choices.add(new Choice(Messages.get("wizard.custom"), DestinationConfig.of("My receiver", "localhost", 2575)));
        for (DestinationPresets.Preset p : DestinationPresets.all()) {
            choices.add(new Choice(p.name(), p.config()));
        }
        preset.setItems(FXCollections.observableArrayList(choices));
        preset.setId("wizardPresetBox");
        preset.valueProperty().addListener((o, a, c) -> {
            if (c != null) {
                name.setText(c.template().name());
                host.setText(c.template().host());
                port.setText(String.valueOf(c.template().port()));
                waitForAck.setSelected(c.template().ackMode() == AckMode.EXPECT_ACK);
            }
        });
        preset.setValue(choices.get(0));
        name.setId("wizardNameField");
        host.setId("wizardHostField");
        host.setPromptText(Messages.get("wizard.host.prompt"));
        waitForAck.setId("wizardWaitForAckBox");
        waitForAck.setSelected(true);

        GridPane grid = new GridPane();
        grid.getStyleClass().add("form-pane");
        grid.addRow(0, Fields.label(Messages.get("wizard.preset")), preset);
        grid.addRow(1, Fields.label(Messages.get("wizard.name")), name);
        grid.addRow(2, Fields.label(Messages.get("wizard.host")), host, Fields.label(Messages.get("wizard.port")),
                port);
        grid.addRow(3, new Label(), waitForAck);
        grid.disableProperty().bind(useReceiver.selectedProperty().not());

        Label intro = new Label(Messages.get("wizard.intro"));
        intro.setWrapText(true);
        return new VBox(10, intro, useListener, useReceiver, grid);
    }

    private boolean createDestination() {
        if (destination != null) {
            return true;
        }
        try {
            DestinationConfig d;
            if (useListener.isSelected()) {
                int p = startTestListener.applyAsInt(2575);
                if (p < 0) {
                    p = startTestListener.applyAsInt(0);
                }
                if (p < 0) {
                    error.setText(Messages.get("wizard.listenerFailed"));
                    return false;
                }
                d = DestinationConfig.of(uniqueName(Messages.get("wizard.listenerName")), "127.0.0.1", p)
                        .withNotes(Messages.get("wizard.listenerNotes"));
            } else {
                Choice c = preset.getValue();
                DestinationConfig template = c == null ? DestinationConfig.of("x", "localhost", 2575) : c.template();
                String h = host.getText() == null ? "" : host.getText().trim();
                if (h.isEmpty()) {
                    error.setText(Messages.get("wizard.hostRequired"));
                    return false;
                }
                d = template.withName(uniqueName(name.getText() == null || name.getText().isBlank()
                                ? h : name.getText().trim()))
                        .withAddress(h, Fields.parse(port, Messages.get("wizard.port"), 1, 65_535))
                        .withAckMode(waitForAck.isSelected() ? AckMode.EXPECT_ACK : AckMode.NO_ACK);
            }
            destination = engine.saveDestination(d);
            return true;
        } catch (IllegalArgumentException | QueueException e) {
            error.setText(e.getMessage());
            return false;
        }
    }

    private String uniqueName(String wanted) {
        Set<String> taken = new HashSet<>();
        engine.destinations().forEach(d -> taken.add(d.name()));
        return DestinationProfiles.uniqueName(wanted, taken);
    }

    // ---------------------------------------------------------------------------------------------
    // Step 2: test the connection

    private Node testStep() {
        target.setId("wizardTarget");
        target.setWrapText(true);
        testResult.setId("wizardTestResult");
        testResult.setWrapText(true);
        Button test = new Button(Messages.get("wizard.test"));
        test.setId("wizardTestButton");
        test.setOnAction(e -> testConnection(test));
        Label hint = new Label(Messages.get("wizard.test.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("field-label");
        return new VBox(10, target, test, testResult, hint);
    }

    private void testConnection(Button button) {
        DestinationConfig d = destination;
        Task<String> task = new Task<>() {
            @Override
            protected String call() throws IOException {
                Optional<TlsOptions> tls = engine.tlsOptions(d);
                try (MllpClient client = new MllpClient(d.clientConfig(), tls.orElse(null))) {
                    client.connect();
                    return Messages.get(client.isTls() ? "wizard.test.okTls" : "wizard.test.ok", d.address());
                }
            }
        };
        task.setOnSucceeded(e -> {
            button.setDisable(false);
            Styles.badge(testResult, io.hl7sender.core.send.SendOutcome.Severity.SUCCESS);
            testResult.setText(task.getValue());
        });
        task.setOnFailed(e -> {
            button.setDisable(false);
            Styles.badge(testResult, io.hl7sender.core.send.SendOutcome.Severity.FAILURE);
            Throwable t = task.getException();
            testResult.setText(Messages.get("wizard.test.failed", t == null ? "?" : t.getMessage()));
        });
        button.setDisable(true);
        testResult.getStyleClass().removeAll(Styles.BADGE);
        testResult.setText(Messages.get("wizard.test.running"));
        context.executor().submit(task);
    }

    // ---------------------------------------------------------------------------------------------
    // Step 3: send a sample

    private Node sendStep() {
        sample.setItems(FXCollections.observableArrayList(SampleMessages.all()));
        sample.setId("wizardSampleBox");
        sample.getSelectionModel().selectFirst();
        sendResult.setId("wizardSendResult");
        sendResult.setWrapText(true);
        Button send = new Button(Messages.get("wizard.send"));
        send.setId("wizardSendButton");
        send.setOnAction(e -> sendSample(send));
        Label hint = new Label(Messages.get("wizard.send.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("field-label");
        return new VBox(10, sample, send, sendResult, hint);
    }

    private void sendSample(Button button) {
        DestinationConfig d = destination;
        SampleMessages.Sample s = sample.getValue();
        if (d == null || s == null) {
            return;
        }
        Task<String> task = new Task<>() {
            @Override
            protected String call() throws InterruptedException {
                EnqueueResult r = engine.enqueue(d.id(), s.text(), SendOptions.DEFAULTS, "wizard");
                if (!r.accepted()) {
                    return Messages.get("wizard.send.invalid", r.validation().errors().get(0).message());
                }
                long id = r.message().orElseThrow().id();
                long deadline = System.currentTimeMillis() + 30_000;
                QueuedMessage m = r.message().get();
                while (System.currentTimeMillis() < deadline) {
                    m = engine.store().message(id).orElse(m);
                    if (m.status() == MessageStatus.ACKNOWLEDGED || m.status() == MessageStatus.SENT_UNCONFIRMED
                            || m.status() == MessageStatus.DEAD_LETTER) {
                        break;
                    }
                    Thread.sleep(100);
                }
                return switch (m.status()) {
                    case ACKNOWLEDGED -> Messages.get("wizard.send.acked", m.controlId());
                    case SENT_UNCONFIRMED -> Messages.get("wizard.send.noAck", m.controlId());
                    case DEAD_LETTER -> Messages.get("wizard.send.dead", m.lastError().orElse("-"));
                    default -> Messages.get("wizard.send.pending", m.lastError().orElse("-"));
                };
            }
        };
        task.setOnSucceeded(e -> {
            button.setDisable(false);
            sendResult.setText(task.getValue());
        });
        task.setOnFailed(e -> {
            button.setDisable(false);
            sendResult.setText(String.valueOf(task.getException()));
        });
        button.setDisable(true);
        sendResult.setText(Messages.get("wizard.send.running"));
        context.executor().submit(task);
    }
}
