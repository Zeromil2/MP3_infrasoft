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
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;

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
    private int tempoTotal;
    private int currentFrame = 0;
    private int estaTocando = 1;     // 0 = Não está tocando; 1 = Está tocando;
    private int indice = 0; // = this.window.getSelectedSongIndex();
    private boolean proximaMusica = false;
    private boolean musicaAnterior = false;
    private boolean semaforoScrubber = true;
    private boolean estaEmLoop = false;
    private Song musicaAtual; // = listaDeMusicas.get(indice);
    private Thread threadDaMusica;
    private Semaphore semaforoPause = new Semaphore(1);

    private final ActionListener buttonListenerPlayNow = e -> {
        iniciarNovaThread();
    };

    private final ActionListener buttonListenerRemove = e -> {
        int idxMusicaSelec = this.window.getSelectedSongIndex();
        int idxMusicaAtual = listaDeMusicas.indexOf(musicaAtual);
        if (idxMusicaSelec == idxMusicaAtual) {
            interromperThread(threadDaMusica, bitstream, device); // para a reprodução caso remova a música que esteja tocando
            EventQueue.invokeLater(() -> window.resetMiniPlayer());
        }
        listaDeMusicas.remove(listaDeMusicas.get(idxMusicaSelec)); // remove da playlist (do tipo Song)
        if (idxMusicaAtual > idxMusicaSelec) indice--;
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
        if (listaDeMusicas.isEmpty() && estaEmLoop) estaEmLoop = false; // Reseta a flag para no caso em que todas as músicas sejam removidas
        EventQueue.invokeLater(() -> this.window.setQueueList(infoDasMusicas));
    };

    private final ActionListener buttonListenerAddSong = e -> {
        Song musica = this.window.openFileChooser();
        if (musica != null) {
            listaDeMusicas.add(musica); // adicionando o arquivo mp3 selecionado ao arraylist da classe Song
            String[] dadosDaMusica = musica.getDisplayInfo();
            int tamanho = infoDasMusicas.length;
            infoDasMusicas = Arrays.copyOf(infoDasMusicas, tamanho+1);
            infoDasMusicas[tamanho] = dadosDaMusica; // salvando os dados da música numa matriz de String
            EventQueue.invokeLater(() -> this.window.setQueueList(infoDasMusicas));
        }
    };

    private final ActionListener buttonListenerPlayPause = e -> {
        if (estaTocando == 1) {
            estaTocando = 0;
        } else {
            estaTocando = 1;
            semaforoPause.release();
        }
        EventQueue.invokeLater(() -> window.setPlayPauseButtonIcon(estaTocando));
    };

    private final ActionListener buttonListenerStop = e -> {
        interromperThread(threadDaMusica, bitstream, device);
        if (estaEmLoop) estaEmLoop = false; // Desabilita a flag caso a música seja parada
        EventQueue.invokeLater(() -> window.resetMiniPlayer());
    };

    private final ActionListener buttonListenerNext = e -> {
        proximaMusica = true;
        semaforoPause.release();
        iniciarNovaThread();
    };

    private final ActionListener buttonListenerPrevious = e -> {
        musicaAnterior = true;
        semaforoPause.release();
        iniciarNovaThread();
    };

    private final ActionListener buttonListenerShuffle = e -> {};

    private final ActionListener buttonListenerLoop = e -> {
        if (!estaEmLoop) estaEmLoop = true;
        else estaEmLoop = false;

    };

    private final MouseInputAdapter scrubberMouseInputAdapter = new MouseInputAdapter() {
        int estadoAnterior;
        int novoFrame;
        @Override
        public void mouseReleased(MouseEvent e) {
            // Zera o frame atual e pausa a música momentaneamente
            currentFrame = 0;
            estaTocando = 0;
            try {
                bitstream.close();
                device.close();
                criarObjetos();
                skipToFrame(novoFrame);

                // Retorna ao estado anterior da música após o skip
                estaTocando = estadoAnterior;
                semaforoPause.release();
            } catch (BitstreamException exception) {
                throw new RuntimeException();
            }
            EventQueue.invokeLater(() -> window.setTime((int)(novoFrame * musicaAtual.getMsPerFrame()), tempoTotal));
            semaforoScrubber = true;
        }
        @Override
        public void mousePressed(MouseEvent e) {
            estadoAnterior = estaTocando;
            novoFrame = (int)(window.getScrubberValue() / musicaAtual.getMsPerFrame());
            semaforoScrubber = false;
        }
        @Override
        public void mouseDragged(MouseEvent e) {
            estadoAnterior = estaTocando;
            novoFrame = (int)(window.getScrubberValue() / musicaAtual.getMsPerFrame());
            EventQueue.invokeLater(() -> window.setTime((int)(novoFrame * musicaAtual.getMsPerFrame()), tempoTotal));
        }
    };

    public Player() {
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
            currentFrame++;
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
        if (proximaMusica) indice++;
        else if (musicaAnterior) indice--;
        else if (estaEmLoop && indice == listaDeMusicas.size()-1) indice = 0;
        else indice = this.window.getSelectedSongIndex();
        musicaAtual = listaDeMusicas.get(indice);
        criarObjetos();

        // Reseta as variáveis da música
        currentFrame = 0;
        estaTocando = 1;
        proximaMusica = false;
        musicaAnterior = false;
        EventQueue.invokeLater(() -> window.setPlayingSongInfo(musicaAtual.getTitle(), musicaAtual.getAlbum(), musicaAtual.getArtist()));

        novaThread();
    }

    private void novaThread() {
        threadDaMusica = new Thread(() -> {
            tempoTotal = (int)(musicaAtual.getNumFrames() * musicaAtual.getMsPerFrame());
            while (true) {
                // Tenta adquirir o semáforo se a música estiver pausada, para então resumir a música
                try {
                    if (estaTocando == 0) semaforoPause.acquire();
                } catch (InterruptedException exception) {
                    exception.printStackTrace();
                }

                if (estaTocando == 1) {
                    EventQueue.invokeLater(() -> {
                        if (semaforoScrubber) window.setTime((int) (currentFrame * musicaAtual.getMsPerFrame()), tempoTotal);
                        window.setPlayPauseButtonIcon(estaTocando);
                        window.setEnabledPlayPauseButton(true);
                        window.setEnabledStopButton(true);
                        window.setEnabledPreviousButton(indice != 0);
                        window.setEnabledNextButton(indice != listaDeMusicas.size() - 1);
                        window.setEnabledScrubber(true);
                        window.setEnabledLoopButton(!listaDeMusicas.isEmpty());
                    });

                    try {
                        if (!this.playNextFrame()) {
                            if (indice != listaDeMusicas.size()-1) proximaMusica = true;
                            break;
                        }
                    } catch (JavaLayerException exception) {
                        throw new RuntimeException();
                    }
                }
            }
            if (proximaMusica || estaEmLoop) iniciarNovaThread();
            else {
                interromperThread(threadDaMusica, bitstream, device);
                EventQueue.invokeLater(() -> window.resetMiniPlayer());
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
}
