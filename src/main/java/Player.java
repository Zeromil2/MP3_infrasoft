import javazoom.jl.decoder.*;
import javazoom.jl.player.AudioDevice;
import javazoom.jl.player.FactoryRegistry;
import support.PlayerWindow;
import support.Playlist;
import support.Song;

import javax.swing.event.MouseInputAdapter;
import java.awt.*;
import java.awt.event.ActionListener;
import java.awt.event.MouseEvent;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
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
    private Playlist listaDeMusicas = new Playlist();
    private String[][] infoDasMusicas = new String[0][];
    private int flagMusicaRemovida;
    private int tempoTotal;
    private int currentFrame = 0;
    private int estaTocando = 1; // 0 = Não está tocando; 1 = Está tocando;
    private int indice = 0;
    private boolean proximaMusica = false;
    private boolean musicaAnterior = false;
    private boolean semaforoScrubber = true;
    private boolean estaParado = true;
    private Song musicaAtual;
    private Thread threadDaMusica;
    private Semaphore semaforoPause = new Semaphore(1);

    private final ActionListener buttonListenerPlayNow = e -> {
        indice = this.window.getSelectedSongIndex();
        iniciarNovaThread();
    };

    private final ActionListener buttonListenerRemove = e -> {
        int idxMusicaSelec = this.window.getSelectedSongIndex();

        // Entra no if se tiver alguma música tocando, se não, apenas remove a música
        if (!estaParado) {
            if (idxMusicaSelec < listaDeMusicas.getCurrentIndex()) { // Se alguma música antes da tocada for removida
                listaDeMusicas.remove(idxMusicaSelec);
                listaDeMusicas.setCurrentIndex(--indice);
            } else if (listaDeMusicas.getCurrentIndex() == idxMusicaSelec) { // Se a música sendo tocada for removida
                // Se tiver próxima música ou se a playlist estiver em loop
                if (idxMusicaSelec < listaDeMusicas.size()-1 || listaDeMusicas.isLooping()) {
                    listaDeMusicas.remove(idxMusicaSelec);
                    iniciarNovaThread();
                } else {
                    listaDeMusicas.remove(idxMusicaSelec);
                    interromperThread(threadDaMusica, bitstream, device);
                    EventQueue.invokeLater(() -> this.window.resetMiniPlayer());
                }
            }
        } else {
            listaDeMusicas.remove(idxMusicaSelec);
        }

        // Remove da matriz de String
        int tamanho = infoDasMusicas.length;
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
        EventQueue.invokeLater(() -> this.window.setQueueList(infoDasMusicas));

        if (listaDeMusicas.isEmpty()) {
            EventQueue.invokeLater(() -> {
                window.setEnabledShuffleButton(false);
                window.setEnabledLoopButton(false);
            });
        }
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

        if (!listaDeMusicas.isEmpty()) {
            EventQueue.invokeLater(() -> {
                window.setEnabledShuffleButton(true);
                window.setEnabledLoopButton(true);
            });
        }
    };

    private final ActionListener buttonListenerPlayPause = e -> {
        if (estaTocando == 1) {
            estaTocando = 0; // "bloqueia" o semáforo
        } else {
            estaTocando = 1;
            semaforoPause.release(); // libera o semáforo
        }
        EventQueue.invokeLater(() -> window.setPlayPauseButtonIcon(estaTocando));
    };

    private final ActionListener buttonListenerStop = e -> {
        interromperThread(threadDaMusica, bitstream, device);
        EventQueue.invokeLater(() -> window.resetMiniPlayer());
    };

    private final ActionListener buttonListenerNext = e -> {
        proximaMusica = true;
        semaforoPause.release(); // para o caso da música estar pausada e o semáforo estar bloqueado
        iniciarNovaThread();
    };

    private final ActionListener buttonListenerPrevious = e -> {
        musicaAnterior = true;
        semaforoPause.release();
        iniciarNovaThread();
    };

    private final ActionListener buttonListenerShuffle = e -> {
        listaDeMusicas.toggleShuffle(!estaParado);
        // Reorganiza a lista infoDasMusicas de acordo com a nova ordem
        int i = 0;
        for (String[] dadosDaMusica : listaDeMusicas.getDisplayInfo()) {
            infoDasMusicas[i] = dadosDaMusica; i++;
        }
        // Atualiza o índice da música atual e atualiza as informações das músicas na tela
        indice = listaDeMusicas.getCurrentIndex();
        EventQueue.invokeLater(() -> this.window.setQueueList(infoDasMusicas));
    };

    private final ActionListener buttonListenerLoop = e -> listaDeMusicas.toggleLooping();

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
            semaforoScrubber = false; // para o scrubber não continuar prosseguindo automaticamente
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
        if (proximaMusica) indice = listaDeMusicas.getNextIndex();
        else if (musicaAnterior) indice = listaDeMusicas.getPreviousIndex();
        listaDeMusicas.setCurrentIndex(indice);
        musicaAtual = listaDeMusicas.get(indice);
        criarObjetos();

        // Reseta as variáveis da música
        currentFrame = 0;
        estaTocando = 1;
        proximaMusica = false;
        musicaAnterior = false;
        estaParado = false;
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
                    throw new RuntimeException();
                }

                if (estaTocando == 1) {
                    EventQueue.invokeLater(() -> {
                        if (semaforoScrubber) window.setTime((int) (currentFrame * musicaAtual.getMsPerFrame()), tempoTotal);
                        window.setPlayPauseButtonIcon(estaTocando);
                        window.setEnabledPlayPauseButton(true);
                        window.setEnabledStopButton(true);
                        window.setEnabledPreviousButton(listaDeMusicas.isLooping() || indice > 0);
                        window.setEnabledNextButton(listaDeMusicas.isLooping() || indice < listaDeMusicas.size() - 1);
                        window.setEnabledScrubber(true);
                    });

                    try {
                        if (!this.playNextFrame()) {
                            if (listaDeMusicas.isLooping() || indice != listaDeMusicas.size()-1) proximaMusica = true;
                            break;
                        }
                    } catch (JavaLayerException exception) {
                        throw new RuntimeException();
                    }
                }
            }
            // Interrompe threads ao final da música e recria caso haja uma próxima música
            if (proximaMusica) iniciarNovaThread();
            else {
                interromperThread(threadDaMusica, bitstream, device);
                EventQueue.invokeLater(() -> window.resetMiniPlayer());
            }
        });
        threadDaMusica.start();
    }

    private void interromperThread(Thread threadDaMusica, Bitstream bitstream, AudioDevice device) {
        if (threadDaMusica != null) {
            estaParado = true;
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
