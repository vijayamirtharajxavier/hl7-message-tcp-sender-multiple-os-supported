package io.hl7sender.app;

import io.hl7sender.core.hl7.Hl7Text;
import io.hl7sender.core.queue.AttemptRecord;
import io.hl7sender.core.queue.AuditEvent;
import io.hl7sender.core.queue.QueueStore;
import io.hl7sender.core.queue.QueuedMessage;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;

/** A queued message's content, delivery attempts with raw ACKs, and audit trail. */
final class MessageDetailPane extends TabPane {

    private final QueueStore store;
    private final Hl7Editor messageText;
    private final TableView<AttemptRecord> attemptsTable = new TableView<>();
    private final TextArea attemptAck = new TextArea();
    private final ListView<String> auditList = new ListView<>();

    /** @param idPrefix prefix for node IDs, so several instances can coexist ("" for the Queue tab) */
    MessageDetailPane(QueueStore store, String idPrefix) {
        this.store = store;
        messageText = new Hl7Editor(idPrefix.isEmpty() ? "queueMessageText" : idPrefix + "MessageText");
        messageText.setEditable(false);

        attemptsTable.setId(idPrefix.isEmpty() ? "attemptsTable" : idPrefix + "AttemptsTable");
        attemptsTable.setPlaceholder(new Label("No attempts yet"));
        attemptsTable.getColumns().add(column("#", 40,
                a -> a.applicationAck() ? "app" : String.valueOf(a.attemptNo())));
        attemptsTable.getColumns().add(column("Started", 140, a -> QueuePane.formatTime(a.startedAt())));
        attemptsTable.getColumns().add(column("Outcome", 170, a -> a.outcome().orElse("in progress")));
        attemptsTable.getColumns().add(column("ACK", 50, a -> a.ackCode().orElse("")));
        attemptsTable.getColumns().add(column("Round trip", 80,
                a -> a.roundTripMs().filter(ms -> ms > 0).map(ms -> ms + " ms").orElse("")));
        attemptsTable.getColumns().add(column("Detail", 380, a -> a.detail().orElse("")));
        attemptsTable.getSelectionModel().selectedItemProperty().addListener((o, a, b) ->
                attemptAck.setText(b == null ? ""
                        : b.rawAck().map(MessageDetailPane::display).orElse("(no response)")));
        attemptAck.setId(idPrefix.isEmpty() ? "attemptAckText" : idPrefix + "AttemptAckText");
        attemptAck.setEditable(false);
        Styles.mono(attemptAck);
        SplitPane attempts = new SplitPane(attemptsTable, attemptAck);
        attempts.setDividerPositions(0.6);

        auditList.setId(idPrefix.isEmpty() ? "auditList" : idPrefix + "AuditList");
        auditList.setPlaceholder(new Label("Select a message"));

        getTabs().addAll(new Tab("Message", messageText), new Tab("Attempts and ACKs", attempts),
                new Tab("Audit trail", auditList));
        setTabClosingPolicy(TabClosingPolicy.UNAVAILABLE);
        setPadding(new Insets(0, 10, 10, 0));
    }

    void show(Optional<QueuedMessage> message) {
        if (message.isEmpty()) {
            messageText.setText("");
            attemptsTable.getItems().clear();
            attemptAck.clear();
            auditList.getItems().clear();
            return;
        }
        QueuedMessage msg = message.get();
        if (!display(msg.payload()).equals(messageText.getText())) {
            messageText.setText(display(msg.payload()));
        }
        Integer selectedAttempt = Optional.ofNullable(attemptsTable.getSelectionModel().getSelectedItem())
                .map(AttemptRecord::attemptNo).orElse(null);
        List<AttemptRecord> attempts = store.attempts(msg.id());
        attemptsTable.getItems().setAll(attempts);
        if (!attempts.isEmpty()) {
            AttemptRecord toSelect = attempts.stream().filter(a -> selectedAttempt != null
                    && a.attemptNo() == selectedAttempt).findFirst().orElse(attempts.get(attempts.size() - 1));
            attemptsTable.getSelectionModel().select(toSelect);
        } else {
            attemptAck.clear();
        }
        auditList.getItems().setAll(store.audit(msg.id()).stream().map(MessageDetailPane::describe).toList());
    }

    static String display(String wire) {
        String d = Hl7Text.toDisplay(wire);
        return d.isEmpty() ? wire : d;
    }

    private static String describe(AuditEvent e) {
        String transition = e.fromStatus().isPresent() || e.toStatus().isPresent()
                ? e.fromStatus().orElse("-") + " -> " + e.toStatus().orElse("-") : "";
        return QueuePane.formatTime(e.at()) + "  [" + e.actor() + "]  " + transition
                + e.detail().map(d -> (transition.isEmpty() ? "" : "  ") + d).orElse("");
    }

    private static <T> TableColumn<T, String> column(String title, double width, Function<T, String> value) {
        TableColumn<T, String> col = new TableColumn<>(title);
        col.setPrefWidth(width);
        col.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        return col;
    }
}
