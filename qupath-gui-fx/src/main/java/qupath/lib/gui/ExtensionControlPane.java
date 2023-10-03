package qupath.lib.gui;

import javafx.collections.MapChangeListener;
import javafx.collections.ObservableMap;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.text.Text;
import org.apache.commons.text.WordUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.common.Version;
import qupath.lib.gui.actions.ActionTools;
import qupath.lib.gui.commands.Commands;
import qupath.lib.gui.extensions.GitHubProject;
import qupath.lib.gui.extensions.QuPathExtension;
import qupath.lib.gui.extensions.UpdateChecker;
import qupath.lib.gui.localization.QuPathResources;
import qupath.lib.gui.tools.GuiTools;
import qupath.lib.gui.tools.IconFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.ResourceBundle;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Borderpane that displays extensions, with options to remove,
 * open containing folder, update, where possible.
 */
public class ExtensionControlPane extends BorderPane {

    private static final Logger logger = LoggerFactory.getLogger(QuPathGUI.class);
    private static final Pattern GITHUB_REPO_PATTERN = Pattern.compile(
            "https://github.com/([a-zA-Z0-9-]+)/(qupath-extension-[a-zA-Z0-9]+)/?.*");
    private static final Pattern GITHUB_JAR_PATTERN = Pattern.compile(
            "https://github.com/[0-9a-zA-Z-]+/(qupath-extension-[0-9a-zA-Z-]+)/" +
                   "releases/download/[a-zA-Z0-9-.]+/(qupath-extension-wsinfer-[0-9a-zA-Z-.]+.jar)");

    @FXML
    private ListView<QuPathExtension> coreListView;
    @FXML
    private ListView<QuPathExtension> userListView;

    @FXML
    private Button addBtn;

    @FXML
    private Button rmBtn;
    @FXML
    private Button disableBtn;
    @FXML
    private Button submitBtn;
    @FXML
    private Button openExtensionDirBtn;

    @FXML
    private Button updateBtn;
    @FXML
    private Button submitAddBtn;

    @FXML
    private HBox addHBox;

    @FXML
    private TextField ownerTextArea;
    @FXML
    private TextField repoTextArea;

    /**
     * Create an instance of the ExtensionControlPane UI pane.
     * @return A BorderPane subclass.
     * @throws IOException If FXML or resources can't be found.
     */
    public static ExtensionControlPane createInstance() throws IOException {
        return new ExtensionControlPane();
    }

    private ExtensionControlPane() throws IOException {
        var loader = new FXMLLoader(ExtensionControlPane.class.getResource("ExtensionControlPane.fxml"));
        loader.setController(this);
        loader.setRoot(this);
        loader.setResources(ResourceBundle.getBundle("qupath/lib/gui/localization/qupath-gui-strings"));
        loader.load();
    }


    @FXML
    private void initialize() {
        this.setOnDragDropped(QuPathGUI.getInstance().getDefaultDragDropListener());
        ExtensionManager extensionManager = QuPathGUI.getInstance().getExtensionManager();
        ObservableMap<Class<? extends QuPathExtension>, QuPathExtension> extensions = extensionManager.getLoadedExtensions();
        extensions.addListener((MapChangeListener<Class<? extends QuPathExtension>, QuPathExtension>) c -> {
            if (c.wasAdded()) {
                if (c.getValueAdded().getClass().getClassLoader().getClass().equals(ExtensionClassLoader.class)) {
                    userListView.getItems().add(c.getValueAdded());
                } else {
                    coreListView.getItems().add(c.getValueAdded());
                }
            }
            if (c.wasRemoved()) {
                if (c.getValueAdded().getClass().getClassLoader().getClass().equals(ExtensionClassLoader.class)) {
                    userListView.getItems().remove(c.getValueRemoved());
                } else {
                    coreListView.getItems().remove(c.getValueRemoved());
                }
            }
        });
        openExtensionDirBtn.disableProperty().bind(
                UserDirectoryManager.getInstance().userDirectoryProperty().isNull());
        submitAddBtn.disableProperty().bind(
            repoTextArea.textProperty().isEmpty().or(ownerTextArea.textProperty().isEmpty()));

        coreListView.getItems().addAll(
                extensionManager.getLoadedExtensions().values()
                        .stream()
                        .sorted(Comparator.comparing(QuPathExtension::getName))
                        .filter(e -> !(e.getClass().getClassLoader().getClass().equals(ExtensionClassLoader.class)))
                        .toList());
        coreListView.setCellFactory(ExtensionListCell::new);
        userListView.getItems().addAll(
                extensionManager.getLoadedExtensions().values()
                        .stream()
                        .sorted(Comparator.comparing(QuPathExtension::getName))
                        .filter(e -> e.getClass().getClassLoader().getClass().equals(ExtensionClassLoader.class))
                        .toList());
        userListView.setCellFactory(ExtensionListCell::new);


        ownerTextArea.addEventHandler(KeyEvent.KEY_RELEASED, e -> {
            if (e.getCode() == KeyCode.ENTER) {
                submitAdd();
            }
            if (e.getCode() == KeyCode.ESCAPE) {
                cancelAdd();
            }
        });
        repoTextArea.addEventHandler(KeyEvent.KEY_RELEASED, e -> {
            if (e.getCode() == KeyCode.ENTER) {
                submitAdd();
            }
            if (e.getCode() == KeyCode.ESCAPE) {
                cancelAdd();
            }
        });
    }

    public static void handleGitHubURL(String url) {
        Matcher jarMatcher = GITHUB_JAR_PATTERN.matcher(url);
        if (jarMatcher.matches()) {
            if (!Dialogs.showYesNoDialog(QuPathResources.getString("ExtensionControlPane"),
                    String.format(QuPathResources.getString("ExtensionControlPane.installExtensionFromGithub"), jarMatcher.group(1)))) {
                return;
            }
            logger.debug("Trying to download extension .jar directly");
            var dir = ExtensionControlPane.getExtensionPath();
            if (dir == null) return;
            var outputFile = new File(dir.toString(), jarMatcher.group(2));
            logger.info("Downloading suspected extension {} to extension directory {}", url, outputFile);
            try {
                downloadURLToFile(url, outputFile);
            } catch (IOException e) {
                Dialogs.showErrorNotification(QuPathResources.getString("ExtensionControlPane"),
                        QuPathResources.getString("ExtensionControlPane.unableToDownload"));
            }
            return;
        }
        Matcher repoMatcher = GITHUB_REPO_PATTERN.matcher(url);
        if (!repoMatcher.matches()) {
            logger.debug("URL did not match GitHub extension pattern");
            return;
        }
        logger.info("Trying to download .jar based on release conventions");
        var repo = GitHubProject.GitHubRepo.create("Name", repoMatcher.group(1), repoMatcher.group(2));
        try {
            askToDownload(repo);
        } catch (IOException | URISyntaxException | InterruptedException e) {
            Dialogs.showErrorNotification(QuPathResources.getString("ExtensionControlPane"),
                    QuPathResources.getString("ExtensionControlPane.unableToDownload"));
        }
    }

    public static void askToDownload(GitHubProject.GitHubRepo repo) throws URISyntaxException, IOException, InterruptedException {
        var v = UpdateChecker.checkForUpdate(repo);
        if (v != null && Dialogs.showYesNoDialog(QuPathResources.getString("ExtensionControlPane"),
                String.format(QuPathResources.getString("ExtensionControlPane.installExtensionFromGithub"), repo.getRepo()))) {
            var downloadURL = String.format("https://github.com/%s/%s/releases/download/%s/%s-%s.jar",
                    repo.getOwner(), repo.getRepo(), "v" + v.getVersion().toString(), repo.getRepo(), v.getVersion().toString()
            );
            // https://github.com/qupath/qupath-extension-wsinfer/releases/download/v0.2.0/qupath-extension-wsinfer-0.2.0.jar
            var dir = getExtensionPath();
            if (dir == null) return;
            File f = new File(dir.toString(), repo.getRepo() + "-" + v.getVersion() + ".jar");
            downloadURLToFile(downloadURL, f);
            Dialogs.showInfoNotification(
                    QuPathResources.getString("ExtensionControlPane"),
                    String.format(QuPathResources.getString("ExtensionControlPane.successfullyDownloaded"), repo.getRepo()));
            QuPathGUI.getInstance().getExtensionManager().refreshExtensions(true);
        }
    }

    public static Path getExtensionPath() {
        var dir = ExtensionClassLoader.getInstance().getExtensionDirectory();
        if (dir == null || !Files.isDirectory(dir)) {
            logger.info("No extension directory found!");
            var dirUser = Commands.requestUserDirectory(true);
            if (dirUser == null)
                return null;
            dir = ExtensionClassLoader.getInstance().getExtensionDirectory();
        }
        return dir;
    }

    public static void downloadURLToFile(String downloadURL, File file) throws IOException {
        try (InputStream stream = new URL(downloadURL).openStream()) {
            try (ReadableByteChannel readableByteChannel = Channels.newChannel(stream)) {
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    fos.getChannel().transferFrom(readableByteChannel, 0, Long.MAX_VALUE);
                }
            }
        }
    }

    @FXML
    private void submitAdd() {
        var repo = GitHubProject.GitHubRepo.create("", ownerTextArea.getText(), repoTextArea.getText());
        try {
            askToDownload(repo);
        } catch (URISyntaxException | IOException | InterruptedException e) {
            Dialogs.showErrorNotification(QuPathResources.getString("ExtensionControlPane.unableToDownload"), e);
        }
        cancelAdd();
    }

    @FXML
    private void cancelAdd() {
        ownerTextArea.clear();
        repoTextArea.clear();
    }

    @FXML
    private void openExtensionDir() {
        var dir = ExtensionClassLoader.getInstance().getExtensionDirectory();
        if (dir != null) {
            GuiTools.browseDirectory(dir.toFile());
        } else {
            Dialogs.showErrorNotification(
                    QuPathResources.getString("ExtensionControlPane"),
                    QuPathResources.getString("ExtensionControlPane.noExtensionDirectorySet"));
        }
    }

    private static void removeExtension(QuPathExtension extension) {
        if (extension == null) {
            logger.info("No extension selected, so none can be removed");
            return;
        }
        if (!Dialogs.showYesNoDialog(
                QuPathResources.getString("ExtensionControlPane"),
                String.format(QuPathResources.getString("ExtensionControlPane.confirmRemoveExtension"), extension.getName()))) {
            return;
        }
        try {
            var url = extension.getClass().getProtectionDomain().getCodeSource().getLocation();
            logger.info("Removing extension: {}", url);
            new File(url.toURI().getPath()).delete();
            Dialogs.showInfoNotification(
                    QuPathResources.getString("ExtensionControlPane"),
                    String.format(QuPathResources.getString("ExtensionControlPane.extensionRemoved"), url));
        } catch (URISyntaxException e) {
            logger.error("Exception removing extension: " + extension, e);
        }
    }

    private static void updateExtension(QuPathExtension extension) {
        if (!(extension instanceof GitHubProject project)) {
            Dialogs.showWarningNotification(QuPathResources.getString("ExtensionControlPane"),
                    QuPathResources.getString("ExtensionControlPane.unableToCheckForUpdates"));
            return;
        }
        var version = extension.getVersion();
        try {
            var release = UpdateChecker.checkForUpdate(project.getRepository());
            if (release != null && release.getVersion() != Version.UNKNOWN && version.compareTo(release.getVersion()) < 0) {
                logger.info("Found newer release for {} ({} -> {})", project.getRepository().getName(), version, release.getVersion());
                askToDownload(project.getRepository());
            } else if (release != null) {
                Dialogs.showInfoNotification(
                        QuPathResources.getString("ExtensionControlPane"),
                        String.format(
                                QuPathResources.getString("ExtensionControlPane.noNewerRelease"),
                                project.getRepository().getName(), version, release.getVersion())
                );
            }
        } catch (IOException | URISyntaxException | InterruptedException e) {
            Dialogs.showErrorNotification(QuPathResources.getString("ExtensionControlPane.unableToUpdate"), e);
        }
    }


    /**
     * Controller class for extension list cells
     */
    static class ExtensionListCell extends ListCell<QuPathExtension> {

        private final ExtensionListCellBox box;

        public ExtensionListCell(ListView<QuPathExtension> listView) {
            super();
            box = new ExtensionListCellBox();
        }

        @Override
        public void updateItem(QuPathExtension item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            box.setExtension(item);
            setGraphic(box);

            var tooltipText = item.getName();
            if (item instanceof GitHubProject) {
                tooltipText += "\n" + QuPathResources.getString("ExtensionControlPane.doubleClick");
                var contextMenu = new ContextMenu();
                contextMenu.getItems().add(ActionTools.createMenuItem(
                        ActionTools.createAction(() -> openContainingFolder(item),
                                QuPathResources.getString("ExtensionControlPane.openContainingFolder"))));
                contextMenu.getItems().add(ActionTools.createMenuItem(
                        ActionTools.createAction(() -> updateExtension(item),
                                QuPathResources.getString("ExtensionControlPane.updateExtension"))));
                contextMenu.getItems().add(ActionTools.createMenuItem(
                        ActionTools.createAction(() -> removeExtension(item),
                                QuPathResources.getString("ExtensionControlPane.removeExtension"))));
                this.setContextMenu(contextMenu);
            } else if (item.getClass().getClassLoader().getClass().equals(ExtensionClassLoader.class)) {
                tooltipText += "\n" + QuPathResources.getString("ExtensionControlPane.lackingGitHubInfo");
            } else {
                tooltipText += "\n" + QuPathResources.getString("ExtensionControlPane.coreExtensionCannotBeEdited");
            }

            Tooltip.install(this, new Tooltip(tooltipText));
            setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && event.getButton() == MouseButton.PRIMARY) {
                    if (item instanceof GitHubProject) { // this should always be true anyway...
                        String url = ((GitHubProject)item).getRepository().getUrlString();
                        try {
                            logger.info("Trying to open URL {}", url);
                            GuiTools.browseURI(new URI(url));
                        } catch (URISyntaxException e) {
                            Dialogs.showErrorNotification(
                                    QuPathResources.getString("ExtensionControlPane.unableToOpenGitHubURL") + url,
                                    e);
                        }
                    }
                }
            });
        }

        private void openContainingFolder(QuPathExtension extension) {
            var url = extension.getClass().getProtectionDomain().getCodeSource().getLocation();
            try {
                GuiTools.browseDirectory(new File(url.toURI()));
            } catch (URISyntaxException e) {
                logger.error("Unable to open directory {}", url);
            }
        }

        /**
         * Simple class that just loads the FXML for a list cell
         */
        static class ExtensionListCellBox extends HBox {
            private QuPathExtension extension;
            @FXML
            private HBox btnHBox;
            @FXML
            private Button rmBtn;
            @FXML
            private Button updateBtn;
            @FXML
            private Text nameText, versionText, descriptionText;

            ExtensionListCellBox() {
                var loader = new FXMLLoader(ExtensionControlPane.class.getResource("ExtensionListCellBox.fxml"));
                loader.setController(this);
                loader.setRoot(this);
                loader.setResources(ResourceBundle.getBundle("qupath/lib/gui/localization/qupath-gui-strings"));
                try {
                    loader.load();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                rmBtn.setGraphic(IconFactory.createNode(8, 8, IconFactory.PathIcons.MINUS));
                updateBtn.setGraphic(IconFactory.createNode(8, 8, IconFactory.PathIcons.REFRESH));
            }

            ExtensionListCellBox(QuPathExtension extension) {
                this();
                this.extension = extension;
            }

            QuPathExtension getExtension() {
                return extension;
            }

            void setExtension(QuPathExtension extension) {
                nameText.setText(extension.getName());
                versionText.setText("v" + extension.getVersion().toString());
                descriptionText.setText(WordUtils.wrap(extension.getDescription(), 80));
                // core and non-core extensions have different classloaders;
                // can't remove or update core ones
                if (!extension.getClass().getClassLoader().getClass().equals(ExtensionClassLoader.class)) {
                    btnHBox.getChildren().remove(rmBtn);
                    btnHBox.getChildren().remove(updateBtn);
                }
                // if we don't have GitHub information, we can't update
                // but we can remove
                if (!(extension instanceof GitHubProject)) {
                    updateBtn.setDisable(true);
                }
                this.extension = extension;
            }

            @FXML
            private void updateExtension() {
                ExtensionControlPane.updateExtension(this.extension);
            }
            @FXML
            private void removeExtension() {
                ExtensionControlPane.removeExtension(this.extension);
            }
        }
    }


}
