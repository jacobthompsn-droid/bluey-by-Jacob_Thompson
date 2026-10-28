"""The laptop window: pairing QR, phone/USB status, and the face stream that makes the
phone's eyes follow your mouse. The network runs on its own asyncio thread; Qt stays on
the main thread and hands work over with call_soon_threadsafe and queued signals."""
import argparse
import asyncio
import threading
import time

import qrcode
from PySide6.QtCore import QObject, QPointF, QRectF, QSettings, Qt, QTimer, Signal
from PySide6.QtGui import QColor, QCursor, QGuiApplication, QPainter
from PySide6.QtWidgets import (QApplication, QComboBox, QGridLayout, QHBoxLayout, QLabel, QPushButton,
                               QSlider, QVBoxLayout, QWidget)

from . import storage, usb
from .gaze import GazeTracker
from .link import PhoneServer
from .protocol import DEFAULT_PORT, MOODS


class Bridge(QObject):
    phones_changed = Signal(list)
    usb_status = Signal(bool, str)
    server_failed = Signal(str)


class Network:
    """The PhoneServer on a background asyncio loop."""

    def __init__(self, bridge, record, host, port):
        self.bridge = bridge
        self.loop = asyncio.new_event_loop()
        self.server = PhoneServer(record, storage.laptop_name(), host=host, port=port,
                                  on_phones_changed=bridge.phones_changed.emit)
        threading.Thread(target=self._run, name="bluey-network", daemon=True).start()

    def _run(self):
        asyncio.set_event_loop(self.loop)
        try:
            self.loop.run_until_complete(self.server.start())
        except OSError as error:
            self.bridge.server_failed.emit(f"Couldn't listen on port {self.server.port}: {error.strerror or error}. "
                                           "Is another copy of Bluey already running?")
            return
        self.loop.run_forever()

    def send_face(self, face):
        self.loop.call_soon_threadsafe(self.server.send_face, face)

    def set_record(self, record):
        def swap():
            self.server.record = record
            # Drop phones that paired with the old secret.
            for phone in list(self.server._phones):
                phone.writer.close()
        self.loop.call_soon_threadsafe(swap)


class QRCodeView(QWidget):
    def __init__(self):
        super().__init__()
        self.setMinimumSize(260, 260)
        self.matrix = []

    def set_text(self, text):
        code = qrcode.QRCode(error_correction=qrcode.constants.ERROR_CORRECT_M, border=2)
        code.add_data(text)
        code.make(fit=True)
        self.matrix = code.get_matrix()
        self.update()

    def paintEvent(self, event):
        painter = QPainter(self)
        side = min(self.width(), self.height())
        painter.fillRect(QRectF(0, 0, self.width(), self.height()), QColor("white"))
        if not self.matrix:
            return
        cell = side / len(self.matrix)
        origin = QPointF((self.width() - side) / 2, (self.height() - side) / 2)
        painter.setPen(Qt.NoPen)
        painter.setBrush(QColor("black"))
        for y, row in enumerate(self.matrix):
            for x, dark in enumerate(row):
                if dark:
                    painter.drawRect(QRectF(origin.x() + x * cell, origin.y() + y * cell, cell + 0.5, cell + 0.5))


class MainWindow(QWidget):
    def __init__(self, host, port):
        super().__init__()
        self.setWindowTitle("Bluey (laptop)")
        self.port = port
        self.record = storage.load_or_create(port)
        self.tracker = GazeTracker()
        self.phones = []
        self.usb_busy = False

        self.bridge = Bridge()
        self.bridge.phones_changed.connect(self.on_phones)
        self.bridge.usb_status.connect(self.on_usb)
        self.bridge.server_failed.connect(lambda message: self.phone_label.setText("⚠️ " + message))
        self.network = Network(self.bridge, self.record, host, port)

        self.qr = QRCodeView()
        self.qr.set_text(self.record.qr_text)
        hint = QLabel("On the phone, tap <b>Pair</b> and scan this code.<br>"
                      "Keep it private: it's the key to your laptop's Bluey.")
        hint.setWordWrap(True)
        self.phone_label = QLabel("Phone: waiting…")
        self.usb_label = QLabel("USB: checking…")
        self.usb_label.setWordWrap(True)
        self.phone_label.setWordWrap(True)
        self.face_label = QLabel("")
        self.face_label.setStyleSheet("font-family: monospace; color: #666;")

        self.mood = QComboBox()
        self.mood.addItem("Auto (follow mouse)", None)
        for mood in MOODS:
            self.mood.addItem(mood.capitalize(), mood)
        # Where the phone stands is remembered between runs.
        self.settings = QSettings("Bluey", "Bluey laptop")
        self.monitor = QComboBox()
        self.monitor.currentIndexChanged.connect(self.monitor_chosen)
        self.refresh_monitors()
        QGuiApplication.instance().screenAdded.connect(lambda _: self.refresh_monitors())
        QGuiApplication.instance().screenRemoved.connect(lambda _: QTimer.singleShot(0, self.refresh_monitors))
        self.position = QSlider(Qt.Horizontal)
        self.position.setRange(0, 100)
        self.position.valueChanged.connect(self.position_moved)
        self.position.setValue(int(self.settings.value("phonePosition", 50)))
        self.position_moved(self.position.value())

        usb_button = QPushButton("Set up USB now")
        usb_button.clicked.connect(lambda: self.run_usb(force=True))
        new_code = QPushButton("New pairing code")
        new_code.clicked.connect(self.new_code)

        controls = QGridLayout()
        controls.addWidget(QLabel("Mood:"), 0, 0)
        controls.addWidget(self.mood, 0, 1)
        controls.addWidget(QLabel("Phone sits under:"), 1, 0)
        controls.addWidget(self.monitor, 1, 1)
        controls.addWidget(QLabel("Fine-tune:"), 2, 0)
        row = QHBoxLayout()
        row.addWidget(QLabel("left"))
        row.addWidget(self.position)
        row.addWidget(QLabel("right"))
        controls.addLayout(row, 2, 1)
        buttons = QHBoxLayout()
        buttons.addWidget(usb_button)
        buttons.addWidget(new_code)

        layout = QVBoxLayout(self)
        layout.addWidget(self.qr)
        layout.addWidget(hint)
        layout.addWidget(self.phone_label)
        layout.addWidget(self.usb_label)
        layout.addLayout(controls)
        layout.addLayout(buttons)
        layout.addWidget(self.face_label)

        self.face_timer = QTimer(self)
        self.face_timer.timeout.connect(self.tick)
        self.face_timer.start(33)
        self.usb_timer = QTimer(self)
        self.usb_timer.timeout.connect(self.run_usb)
        self.usb_timer.start(5000)
        self.run_usb(force=True)

    def refresh_monitors(self):
        """Lists the monitors left to right, then each gap between neighbours, keeping the saved
        choice when it's still plugged in. An item's data is its monitor names joined by "|"."""
        screens = sorted(QGuiApplication.screens(), key=lambda s: (s.geometry().x(), s.geometry().y()))
        primary = QGuiApplication.primaryScreen()
        wanted = self.settings.value("phoneScreen") or (primary.name() if primary else "")
        self.monitor.blockSignals(True)
        self.monitor.clear()
        for number, screen in enumerate(screens, 1):
            size = screen.geometry()
            main = " (main)" if screen == primary else ""
            self.monitor.addItem(f"Monitor {number}{main}: {size.width()}×{size.height()}", screen.name())
        for number in range(1, len(screens)):
            self.monitor.addItem(f"Between monitors {number} and {number + 1}",
                                 screens[number - 1].name() + "|" + screens[number].name())
        keys = [self.monitor.itemData(i) for i in range(self.monitor.count())]
        fallback = keys.index(primary.name()) if primary and primary.name() in keys else 0
        self.monitor.setCurrentIndex(keys.index(wanted) if wanted in keys else fallback)
        self.monitor.blockSignals(False)

    def monitor_chosen(self, index):
        if index < 0:
            return
        self.settings.setValue("phoneScreen", self.monitor.itemData(index))
        screens = self.phone_screens()
        if len(screens) == 2:
            # Start the phone at the gap between the two monitors; the slider fine-tunes it.
            left, right = (screen.geometry() for screen in screens)
            area = self.phone_area()
            gap = (left.x() + left.width() + right.x()) / 2
            self.position.setValue(round((gap - area.x()) / area.width() * 100))

    def position_moved(self, value):
        self.tracker.phone_position = value / 100
        self.settings.setValue("phonePosition", value)

    def phone_screens(self):
        """The monitor the phone sits under, or the two it stands between (left first)."""
        by_name = {screen.name(): screen for screen in QGuiApplication.screens()}
        found = [by_name[name] for name in (self.monitor.currentData() or "").split("|") if name in by_name]
        return found or [QGuiApplication.primaryScreen()]

    def phone_area(self):
        screens = self.phone_screens()
        area = screens[0].geometry()
        for screen in screens[1:]:
            area = area.united(screen.geometry())
        return area

    def tick(self):
        # Qt's virtual desktop coordinates (DPI-scaled), so Windows display scaling doesn't skew the gaze.
        def rect(geometry):
            return geometry.x(), geometry.y(), geometry.width(), geometry.height()
        area = self.phone_area()
        desktop = area
        for screen in QGuiApplication.screens():
            desktop = desktop.united(screen.geometry())
        # The smallest sideways glance is half of one monitor, even when the phone spans two.
        reach = min(screen.geometry().width() for screen in self.phone_screens()) * 0.5
        mouse = QCursor.pos()
        face = self.tracker.face(mouse.x(), mouse.y(), rect(area), rect(desktop),
                                 time.monotonic(), self.mood.currentData(), reach=reach)
        self.network.send_face(face)
        self.face_label.setText(f"gaze x {face['gazeX']:+.2f}  y {face['gazeY']:+.2f}  mood {face['mood']}")

    def run_usb(self, force=False):
        # adb reverse is only needed until a phone connects; re-run it after unplugging.
        if self.usb_busy or (self.phones and not force):
            return
        self.usb_busy = True

        def work():
            ok, message = usb.reverse(self.port)
            self.bridge.usb_status.emit(ok, message)
        threading.Thread(target=work, daemon=True).start()

    def on_usb(self, ok, message):
        self.usb_busy = False
        self.usb_label.setText(("USB: " if ok else "USB: ⚠️ ") + message)

    def on_phones(self, names):
        self.phones = names
        self.phone_label.setText("Phone: connected to " + ", ".join(names) if names else "Phone: waiting…")

    def new_code(self):
        self.record = storage.reset(self.port)
        self.qr.set_text(self.record.qr_text)
        self.network.set_record(self.record)


def main(argv=None):
    parser = argparse.ArgumentParser(prog="bluey_laptop", description="Bluey's laptop app")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    parser.add_argument("--host", default="127.0.0.1",
                        help="127.0.0.1 (default) only accepts the USB link; 0.0.0.0 would also accept Wi-Fi")
    args = parser.parse_args(argv)
    app = QApplication([])
    window = MainWindow(args.host, args.port)
    window.resize(380, 640)
    window.show()
    return app.exec()
