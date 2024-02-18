import javazoom.jl.decoder.*;
import javazoom.jl.player.AudioDevice;
import javazoom.jl.player.FactoryRegistry;
import support.PlayerWindow;
import support.Song;

import javax.swing.event.MouseInputAdapter;
import java.awt.*;
import java.awt.event.ActionListener;
import java.awt.event.MouseEvent;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Arrays;

public class Player {

    /**
     * The MPEG audio bitstream.
     */
    private Bitstream bitstream;
    /**
     * The MPEG audio decoder.
     */
    private Decoder decoder;
    /**
     * The AudioDevice where audio samples are written to.
     */
    private AudioDevice device;

    private PlayerWindow window;
    private String[][] infoDasMusicas = new String[0][];
    private ArrayList<Song> listaDeMusicas = new ArrayList<Song>();
    private int currentFrame = 0;
    private int estaTocando = 0;     // 0 = Não está tocando; 1 = Está tocando;
    private int indice; // = this.window.getSelectedSongIndex();
    private Song musicaAtual; // = listaDeMusicas.get(indice);
    private Thread threadDaMusica;

    private final ActionListener buttonListenerPlayNow = e -> {
        iniciarNovaThread();
    };
    private final ActionListener buttonListenerRemove = e -> {
        int idxMusicaSelec = this.window.getSelectedSongIndex();
        int idxMusicaAtual = listaDeMusicas.indexOf(musicaAtual);
        if (idxMusicaSelec == idxMusicaAtual) {
            interromperThread(threadDaMusica, bitstream, device); // para a reprodução caso remova a música que esteja tocando
            window.resetMiniPlayer();
        }
        listaDeMusicas.remove(listaDeMusicas.get(idxMusicaSelec)); // remove da playlist (do tipo Song)
        int tamanho = infoDasMusicas.length;
        // remove da matriz de String
        if (idxMusicaSelec == 0) {
            infoDasMusicas = Arrays.copyOfRange(infoDasMusicas, 1, tamanho);
        } else if (idxMusicaSelec == tamanho-1) {
            infoDasMusicas = Arrays.copyOfRange(infoDasMusicas, 0, tamanho-1);
        } else {
            String[][] parte1 = Arrays.copyOfRange(infoDasMusicas, 0, idxMusicaSelec);
            String[][] parte2 = Arrays.copyOfRange(infoDasMusicas, idxMusicaSelec+1, tamanho);
            infoDasMusicas = Arrays.copyOf(parte1, parte1.length + parte2.length);
            System.arraycopy(parte2, 0, infoDasMusicas, parte1.length, parte2.length);
        }
        this.window.setQueueList(infoDasMusicas);
    };
    private final ActionListener buttonListenerAddSong = e -> {
        Song musica = this.window.openFileChooser();
        listaDeMusicas.add(musica); // adicionando o arquivo mp3 selecionado ao arraylist da classe Song
        String[] dadosDaMusica = musica.getDisplayInfo();
        int tamanho = infoDasMusicas.length;
        infoDasMusicas = Arrays.copyOf(infoDasMusicas, tamanho+1);
        infoDasMusicas[tamanho] = dadosDaMusica; // salvando os dados da música numa matriz de String
        this.window.setQueueList(infoDasMusicas);
    };

    private final ActionListener buttonListenerPlayPause = e -> {
        if (estaTocando == 1) {
            estaTocando = 0;
            window.setPlayPauseButtonIcon(0);
        } else {
            estaTocando = 1;
            window.setPlayPauseButtonIcon(1);
            novaThread();
        }
    };
    private final ActionListener buttonListenerStop = e -> {
        interromperThread(threadDaMusica, bitstream, device);
        window.resetMiniPlayer();
    };
    private final ActionListener buttonListenerNext = e -> {};
    private final ActionListener buttonListenerPrevious = e -> {};
    private final ActionListener buttonListenerShuffle = e -> {};
    private final ActionListener buttonListenerLoop = e -> {};
    private final MouseInputAdapter scrubberMouseInputAdapter = new MouseInputAdapter() {
        @Override
        public void mouseReleased(MouseEvent e) {
        }

        @Override
        public void mousePressed(MouseEvent e) {
        }

        @Override
        public void mouseDragged(MouseEvent e) {
        }
    };

    public Player() {
//        String[][] listaDeMusicas = new String[0][];
        EventQueue.invokeLater(() -> window = new PlayerWindow(
                "CP Playlist",
                infoDasMusicas,
                buttonListenerPlayNow,
                buttonListenerRemove,
                buttonListenerAddSong,
                buttonListenerShuffle,
                buttonListenerPrevious,
                buttonListenerPlayPause,
                buttonListenerStop,
                buttonListenerNext,
                buttonListenerLoop,
                scrubberMouseInputAdapter)
        );
    }

    //<editor-fold desc="Essential">
    /**
     * @return False if there are no more frames to play.
     */
    private boolean playNextFrame() throws JavaLayerException {
        if (device != null) {
            Header h = bitstream.readFrame();
            if (h == null) return false;

            SampleBuffer output = (SampleBuffer) decoder.decodeFrame(h, bitstream);
            device.write(output.getBuffer(), 0, output.getBufferLength());
            bitstream.closeFrame();
        }
        return true;
    }

    /**
     * @return False if there are no more frames to skip.
     */
    private boolean skipNextFrame() throws BitstreamException {
        Header h = bitstream.readFrame();
        if (h == null) return false;
        bitstream.closeFrame();
        currentFrame++;
        return true;
    }

    /**
     * Skips bitstream to the target frame if the new frame is higher than the current one.
     *
     * @param newFrame Frame to skip to.
     */
    private void skipToFrame(int newFrame) throws BitstreamException {
        int framesToSkip = newFrame - currentFrame;
        boolean condition = true;
        while (framesToSkip-- > 0 && condition) condition = skipNextFrame();
    }

    private void iniciarNovaThread() {
        interromperThread(threadDaMusica, bitstream, device);   // Chama a função para interromper a thread atual
        indice = this.window.getSelectedSongIndex();
        musicaAtual = listaDeMusicas.get(indice);
        criarObjetos();

        // Reseta as variáveis da música, setando o frame atual para 0 e toquePause para o estado 1 (de tocar).
        currentFrame = 0;
        estaTocando = 1;
        window.setPlayingSongInfo(musicaAtual.getTitle(), musicaAtual.getAlbum(), musicaAtual.getArtist());

        novaThread();
    }

    private void novaThread() {
        threadDaMusica = new Thread(() -> {
            int tempoTotal = (int) (musicaAtual.getNumFrames() * musicaAtual.getMsPerFrame());
            while (true) {
                if (estaTocando == 1) {
                    window.setTime((int) (currentFrame * musicaAtual.getMsPerFrame()), tempoTotal);
                    window.setPlayPauseButtonIcon(estaTocando);
                    window.setEnabledPlayPauseButton(true);
                    window.setEnabledStopButton(true);
                    try {
                        if (!this.playNextFrame()) {
                            estaTocando = 0;
                            window.resetMiniPlayer();
                        } else {
                            currentFrame++;
                        }
                    } catch (JavaLayerException exception) {
                        throw new RuntimeException();
                    }
                }
            }
        });
        threadDaMusica.start();
    }

    private void interromperThread(Thread threadDaMusica, Bitstream bitstream, AudioDevice device) {
        if (bitstream != null) {
            threadDaMusica.interrupt();
            try {
                bitstream.close();
                device.close();
            } catch (BitstreamException exception) {
                throw new RuntimeException();
            }
        }
    }

    private void criarObjetos() {
        try {
            this.device = FactoryRegistry.systemRegistry().createAudioDevice();
            this.device.open(this.decoder = new Decoder());
            this.bitstream = new Bitstream(musicaAtual.getBufferedInputStream());
        } catch (JavaLayerException | FileNotFoundException exception) {
            throw new RuntimeException();
        }
    }
    //</editor-fold>
}
