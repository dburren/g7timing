// SPDX-FileCopyrightText: 2026 David Burren
// SPDX-License-Identifier: Apache-2.0

import AVFoundation
import SwiftUI
import UIKit
import Shared

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController(scanApplicator: { onCode, onError in
            ApplicatorScanner.present(onCode: { onCode($0) }, onError: { onError($0) })
        })
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    var body: some View {
        ComposeView().ignoresSafeArea()
    }
}

/** Presents the camera to read the 2D code (GS1 Data Matrix) on a G7 sensor's applicator or box. */
enum ApplicatorScanner {
    static func present(onCode: @escaping (String) -> Void, onError: @escaping (String) -> Void) {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            show(onCode: onCode, onError: onError)
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { granted in
                DispatchQueue.main.async {
                    if granted { show(onCode: onCode, onError: onError) }
                    else { onError("Camera access is needed to scan the applicator or box.") }
                }
            }
        default:
            onError("G7timing doesn't have camera access. Allow it in Settings to scan the applicator or box.")
        }
    }

    private static func show(onCode: @escaping (String) -> Void, onError: @escaping (String) -> Void) {
        guard var top = UIApplication.shared.connectedScenes
            .compactMap({ ($0 as? UIWindowScene)?.keyWindow }).first?.rootViewController else { return }
        while let presented = top.presentedViewController { top = presented }
        let vc = ScannerViewController(onCode: onCode, onError: onError)
        vc.modalPresentationStyle = .fullScreen
        top.present(vc, animated: true)
    }
}

final class ScannerViewController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    private let onCode: (String) -> Void
    private let onError: (String) -> Void
    private let session = AVCaptureSession()
    private let hint = UILabel()
    private var done = false
    private var appeared = false
    /** A finish requested before the screen finished appearing; dismissing mid-presentation fails. */
    private var pendingFinish: (() -> Void)?

    init(onCode: @escaping (String) -> Void, onError: @escaping (String) -> Void) {
        self.onCode = onCode
        self.onError = onError
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { fatalError("not used") }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black

        guard let camera = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
              let input = try? AVCaptureDeviceInput(device: camera),
              session.canAddInput(input) else {
            finish { self.onError("The camera couldn't be opened.") }
            return
        }
        session.addInput(input)
        let output = AVCaptureMetadataOutput()
        guard session.canAddOutput(output) else {
            finish { self.onError("The camera couldn't be started.") }
            return
        }
        session.addOutput(output)
        output.setMetadataObjectsDelegate(self, queue: .main)
        output.metadataObjectTypes = [.dataMatrix, .qr].filter { output.availableMetadataObjectTypes.contains($0) }

        let preview = AVCaptureVideoPreviewLayer(session: session)
        preview.videoGravity = .resizeAspectFill
        preview.frame = view.bounds
        view.layer.addSublayer(preview)

        // Applicator codes are small: zoom in a little so they can be read without holding the phone too close to focus.
        if (try? camera.lockForConfiguration()) != nil {
            camera.videoZoomFactor = min(2.0, camera.activeFormat.videoMaxZoomFactor)
            camera.unlockForConfiguration()
        }

        hint.text = "Point the camera at the square code on the sensor's applicator or box"
        hint.textColor = .white
        hint.numberOfLines = 0
        hint.textAlignment = .center
        let cancel = UIButton(type: .system)
        cancel.setTitle("Cancel", for: .normal)
        cancel.titleLabel?.font = .preferredFont(forTextStyle: .headline)
        cancel.addTarget(self, action: #selector(cancelTapped), for: .touchUpInside)
        let panel = UIStackView(arrangedSubviews: [hint, cancel])
        panel.axis = .vertical
        panel.spacing = 12
        panel.isLayoutMarginsRelativeArrangement = true
        panel.directionalLayoutMargins = NSDirectionalEdgeInsets(top: 16, leading: 16, bottom: 16, trailing: 16)
        panel.backgroundColor = UIColor.black.withAlphaComponent(0.7)
        panel.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(panel)
        NSLayoutConstraint.activate([
            panel.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            panel.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            panel.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            cancel.bottomAnchor.constraint(lessThanOrEqualTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -8),
        ])

        DispatchQueue.global(qos: .userInitiated).async { self.session.startRunning() }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        view.layer.sublayers?.compactMap { $0 as? AVCaptureVideoPreviewLayer }.forEach { $0.frame = view.bounds }
    }

    func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput objects: [AVMetadataObject], from connection: AVCaptureConnection) {
        guard !done else { return }
        let texts = objects.compactMap { ($0 as? AVMetadataMachineReadableCodeObject)?.stringValue }
        if let code = texts.first(where: { MainViewControllerKt.isApplicatorCode(text: $0) }) {
            finish { self.onCode(code) }
        } else if !texts.isEmpty {
            hint.text = "That code isn't from a G7 applicator or box. Try the square code."
        }
    }

    @objc private func cancelTapped() { finish {} }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        appeared = true
        if let f = pendingFinish {
            pendingFinish = nil
            dismiss(animated: true, completion: f)
        }
    }

    private func finish(then: @escaping () -> Void) {
        guard !done else { return }
        done = true
        DispatchQueue.global(qos: .userInitiated).async { self.session.stopRunning() }
        if appeared { dismiss(animated: true, completion: then) } else { pendingFinish = then }
    }
}
