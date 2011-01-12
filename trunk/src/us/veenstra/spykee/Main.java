// Copyright 2011 Jack Veenstra
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package us.veenstra.spykee;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.UnknownHostException;

import us.veenstra.spykee.Spykee.DockState;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * This is the main activity that is created when the Spykee app is launched.
 * This handles all the UI.
 */
public class Main extends Activity implements View.OnClickListener,
        MediaPlayer.OnCompletionListener, MediaPlayer.OnErrorListener {
	private static final String TAG = "Main";
	private static final int DIALOG_CONNECT_ID = 1;
	private static final int DIALOG_SOUNDFX_ID = 2;

	// The following strings are used as keys for reading and writing the
	// values needed to connect to Spykee.
	private static final String PREFS_HOST = "host";
	private static final String PREFS_PORT = "port";
	private static final String PREFS_LOGIN = "login";
	private static final String PREFS_PASSWORD = "password";

	// The name of the directory under /sdcard/ where we store temporary files.
	private static final String SPYKEE_DIR = "spykee";

	// The File object for the storage directory.
    private static File sStorageRoot;

    // The number of audio buffer files that we cycle through. Each audio
    // packet from Spyke usually contains 1/8 second of sound (2000 16-bit
    // samples from a 16KHz stream), but sometimes packets are delayed
    // and contain more than 2000 audio samples.
    // Since the audio buffers don't arrive in precise intervals, we need
    // to buffer them and play them slightly delayed from real time.
    private static final int MAX_AUDIO_BUFFERS = 16;

    // If the number of audio buffers that we have downloaded gets too far
    // ahead of the playback, then we will start dropping buffers to catch
    // up.
    private static final int DROP_AUDIO_THRESHOLD = 8;

    // The number of buffers we have downloaded but not yet played.
    private static int sNumAudioBuffers;

    // The number of the audio buffer that we are downloading (wraps to zero).
    private static int sDownloadingAudioNum;

    // The number of the audio buffer that we are playing (wraps to zero).
    private int mPlayingAudioNum;
    
    // Keep track of the number of times we had to wait for an audio
    // buffer to download, and the number of times that we had to skip
    // an audio buffer because playback was falling behind.
    private int mNumWaits;
    private int mNumSkips;

    /**
     * The file header for a .wav file.  We have to fill in the "Chunksize"
     * and the "data size" bytes.  This is used for creating a sound file
     * from the raw audio bytes that Spykee sends us.
     */
    private static byte[] sWaveHeader = {
		0x52, 0x49, 0x46, 0x46,   // "RIFF"
		0, 0, 0, 0,               // Chunksize (little-endian) = data size + 36
		0x57, 0x41, 0x56, 0x45,   // "WAVE"
		0x66, 0x6d, 0x74, 0x20,   // "fmt "
		16, 0, 0, 0,              // subchunk1 size
		1, 0, 1, 0,               // PCM, mono
		(byte) 0x80, 0x3e, 0, 0,  // sample rate = 16KHz
		(byte) 0x80, 0x3e, 0, 0,  // byte rate = 16000
		1, 0, 8, 0,               // Channels * bytes/sample, bits/sample
		0x64, 0x61, 0x74, 0x61,   // "data"
		0, 0, 0, 0                // data size (little-endian)
    };

    /** The starting byte offset for the "Chunksize" field in the wave header */
    private static final int WAVE_CHUNK_SIZE_OFFSET = 4;

    /** The starting byte offset for the "data size" field in the wave header */
    private static final int WAVE_DATA_SIZE_OFFSET = 40;

    // The Spykee object that we use for communicating with the Spykee robot.
    private Spykee mSpykee;

	private Dialog mConnectDialog;
	private Button mConnectButton;
	private Button mDockButton;
	private Button mSoundFxButton;
	private Button mAlarmButton;
	private Button mBombButton;
	private Button mLazerButton;
	private Button mAhAhAhButton;
	private Button mEngineButton;
	private Button mRobotButton;
	private Button mCustom1Button;
	private Button mCustom2Button;
	private TextView mConnectionStatus;
	private TextView mBatteryLevelView;
	private ImageView mCameraView;
    private MediaPlayer mMediaPlayer;

    private class SpykeeHandler extends Handler {
    	@Override
    	public void handleMessage(Message msg) {
    		switch (msg.what) {
    		case Spykee.SPYKEE_BATTERY_LEVEL:
    			mBatteryLevelView.setText(getString(R.string.battery_level, msg.arg1));
    			break;
    		case Spykee.SPYKEE_DOCK:
    			if (msg.arg1 == Spykee.SPYKEE_DOCK_DOCKED) {
    				mDockButton.setText(R.string.undock);
    			} else if (msg.arg1 == Spykee.SPYKEE_DOCK_UNDOCKED) {
    				mDockButton.setText(R.string.dock);
    			}
    			break;
    		case Spykee.SPYKEE_VIDEO_FRAME:
    			Bitmap bitmap = (Bitmap) msg.obj;
    			mCameraView.setImageBitmap(bitmap);
    			break;
    		case Spykee.SPYKEE_AUDIO:
    			if (mMediaPlayer == null) {
    				return;
    			}
    			sNumAudioBuffers += 1;
    			if (sNumAudioBuffers >= DROP_AUDIO_THRESHOLD) {
    				mNumSkips += 1;
    				sNumAudioBuffers -= 1;
    				mPlayingAudioNum += 1;
    				if (mPlayingAudioNum >= MAX_AUDIO_BUFFERS) {
    					mPlayingAudioNum = 0;
    				}
    				Log.d(TAG, "audio skips: " + mNumSkips + " waits: " + mNumWaits);
    			}
    			if (!mMediaPlayer.isPlaying() && sNumAudioBuffers == 1) {
        			playNextAudioFile();
    			}
    		}
    	}
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        mConnectButton = (Button) findViewById(R.id.connect);
        mConnectButton.setOnClickListener(this);
        mDockButton = (Button) findViewById(R.id.dock);
        mDockButton.setOnClickListener(this);
        mSoundFxButton = (Button) findViewById(R.id.soundfx);
        mSoundFxButton.setOnClickListener(this);
        mConnectionStatus = (TextView) findViewById(R.id.status);
        mBatteryLevelView = (TextView) findViewById(R.id.battery_level);
        mCameraView = (ImageView) findViewById(R.id.camera);
		sStorageRoot = Environment.getExternalStorageDirectory();
		if (!sStorageRoot.canWrite()) {
			Log.w(TAG, "Cannot write to external storage: " + sStorageRoot.getAbsolutePath());
		} else {
			// Create the spykee directory if it doesn't exist
			File dir = new File(sStorageRoot, SPYKEE_DIR);
			if (!dir.exists()) {
				dir.mkdir();
			}
			sStorageRoot = dir;
		}
		sNumAudioBuffers = 0;
		sDownloadingAudioNum = 0;
		mPlayingAudioNum = 0;
        mMediaPlayer = new MediaPlayer();
        mMediaPlayer.setOnCompletionListener(this);
        mMediaPlayer.setOnErrorListener(this);
        mSpykee = new Spykee(new SpykeeHandler());
    }

    @Override
    public void onDestroy() {
    	super.onDestroy();
    	mSpykee.close();
    	mMediaPlayer.stop();
    	mMediaPlayer.release();
    	mMediaPlayer = null;
    	deleteAudioFiles();
    }

    /**
     * This is called when a dialog is created for the first time.  The given
     * "id" is the same value that is passed to showDialog().
     */
    @Override
    protected Dialog onCreateDialog(int id) {
    	LayoutInflater inflater = (LayoutInflater) getSystemService(LAYOUT_INFLATER_SERVICE);
    	View layout;
    	AlertDialog.Builder builder = new AlertDialog.Builder(this);
    	switch (id) {
    	case DIALOG_CONNECT_ID:
        	layout = inflater.inflate(R.layout.connect, null);
        	builder.setTitle(R.string.connect);
        	builder.setView(layout);
        	builder.setPositiveButton(R.string.connect, new DialogInterface.OnClickListener() {
    			public void onClick(DialogInterface arg0, int arg1) {
    				doConnect();
    			}
    		});
        	mConnectDialog = builder.create();
        	return mConnectDialog;
    	case DIALOG_SOUNDFX_ID:
        	layout = inflater.inflate(R.layout.soundfx, null);
        	builder.setTitle(R.string.soundfx);
        	builder.setView(layout);
        	builder.setPositiveButton(R.string.close, null);
        	return builder.create();
    	}
    	return null;
    }

    /**
     * This is called each time a dialog is shown.
     */
    @Override
    protected void onPrepareDialog(int id, Dialog dialog) {
    	if (id == DIALOG_CONNECT_ID) {
    		// Read the login settings (if any) from the preferences file.
    		SharedPreferences prefs = getPreferences(MODE_PRIVATE);
    		String host = prefs.getString(PREFS_HOST, null);
    		String port = prefs.getString(PREFS_PORT, null);
    		String login = prefs.getString(PREFS_LOGIN, null);
    		String password = prefs.getString(PREFS_PASSWORD, null);

    		// Pre-fill the text fields with the saved login settings.
    		EditText editText;
    		if (host != null) {
    			editText = (EditText) dialog.findViewById(R.id.host);
    			editText.setText(host);
    		}
    		if (port != null) {
    			editText = (EditText) dialog.findViewById(R.id.port);
    			editText.setText(port);
    		}
    		if (login != null) {
    			editText = (EditText) dialog.findViewById(R.id.login);
    			editText.setText(login);
    		}
    		if (password != null) {
    			editText = (EditText) dialog.findViewById(R.id.password);
    			editText.setText(password);
    		}
    	} else if (id == DIALOG_SOUNDFX_ID) {
    		if (mAlarmButton != null) {
    			return;
    		}
        	mAlarmButton = (Button) dialog.findViewById(R.id.alarm_button);
        	mBombButton = (Button) dialog.findViewById(R.id.bomb_button);
        	mLazerButton = (Button) dialog.findViewById(R.id.lazer_button);
        	mAhAhAhButton = (Button) dialog.findViewById(R.id.ahahah_button);
        	mEngineButton = (Button) dialog.findViewById(R.id.engine_button);
        	mRobotButton = (Button) dialog.findViewById(R.id.robot_button);
        	mCustom1Button = (Button) dialog.findViewById(R.id.custom1_button);
        	mCustom2Button = (Button) dialog.findViewById(R.id.custom2_button);
        	mAlarmButton.setOnClickListener(this);
        	mBombButton.setOnClickListener(this);
        	mLazerButton.setOnClickListener(this);
        	mAhAhAhButton.setOnClickListener(this);
        	mEngineButton.setOnClickListener(this);
        	mRobotButton.setOnClickListener(this);
        	mCustom1Button.setOnClickListener(this);
        	mCustom2Button.setOnClickListener(this);
    	}
    }

    /**
     * Connects to the Spykee robot by passing it the login and password.
     */
    private void doConnect() {
    	// Read the login settings from the text fields.
    	EditText editText = (EditText) mConnectDialog.findViewById(R.id.host);
		String host = editText.getText().toString();
    	editText = (EditText) mConnectDialog.findViewById(R.id.port);
		String port = editText.getText().toString();
    	editText = (EditText) mConnectDialog.findViewById(R.id.login);
		String login = editText.getText().toString();
    	editText = (EditText) mConnectDialog.findViewById(R.id.password);
		String password = editText.getText().toString();
		
		// Save the current login settings
		SharedPreferences prefs = getPreferences(MODE_PRIVATE);
		SharedPreferences.Editor editor = prefs.edit();
		editor.putString(PREFS_HOST, host);
		editor.putString(PREFS_PORT, port);
		editor.putString(PREFS_LOGIN, login);
		editor.putString(PREFS_PASSWORD, password);
		editor.commit();

    	mConnectionStatus.setText(getString(R.string.connecting, host));
		int portNum = Integer.parseInt(port);
		try {
    		mSpykee.connect(host, portNum, login, password);
		} catch (UnknownHostException e) {
			Log.e(TAG, "Unknown host: " + host);
			String mesg = getString(R.string.unknown_host, host);
    		mConnectionStatus.setText(mesg);
    		return;
		} catch (IOException e) {
			Log.e(TAG, host + ":" + port + ": " + e);
			String mesg = getString(R.string.io_exception, host, port, e.toString());
    		mConnectionStatus.setText(mesg);
    		return;
		}
    	mConnectionStatus.setText(getString(R.string.connected, host));
    	mSpykee.activate();
    	mDockButton.setEnabled(true);
    	mSoundFxButton.setEnabled(true);
    }

    public void onClick(View view) {
    	if (view == mConnectButton) {
    		showDialog(DIALOG_CONNECT_ID);
    	} else if (view == mDockButton) {
    		if (mSpykee.getDockState() == DockState.DOCKED) {
    			mSpykee.undock();
    		} else if (mSpykee.getDockState() == DockState.UNDOCKED) {
    			mSpykee.dock();
    			mDockButton.setText(R.string.cancel_dock);
    		} else {
    			mSpykee.cancelDock();
    			mDockButton.setText(R.string.dock);
    		}
    	} else if (view == mSoundFxButton) {
    		showDialog(DIALOG_SOUNDFX_ID);
    	} else if (view == mAlarmButton) {
    		mSpykee.playSoundAlarm();
    	} else if (view == mBombButton) {
    		mSpykee.playSoundBomb();
    	} else if (view == mLazerButton) {
    		mSpykee.playSoundLazer();
    	} else if (view == mAhAhAhButton) {
    		mSpykee.playSoundAhAhAh();
    	} else if (view == mEngineButton) {
    		mSpykee.playSoundEngine();
    	} else if (view == mRobotButton) {
    		mSpykee.playSoundRobot();
    	} else if (view == mCustom1Button) {
    		mSpykee.playSoundCustom1();
    	} else if (view == mCustom2Button) {
    		mSpykee.playSoundCustom2();
    	}
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent msg) {
    	// This code works for both the trackball and the Dpad.
    	if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            mSpykee.moveForward();
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            mSpykee.moveBackward();
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            mSpykee.moveLeft();
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            mSpykee.moveRight();
            return true;
        }
        return super.onKeyDown(keyCode, msg);
    }

	public void onCompletion(MediaPlayer player) {
		playNextAudioFile();
	}

	public boolean onError(MediaPlayer mp, int what, int extra) {
		Log.e(TAG, "onError(): " + what + " " + extra);
		return false;
	}

	/**
	 * Constructs the filename for the audio buffer from the index.
	 * @param index the buffer number
	 * @return the filename
	 */
	private static String getAudioFilename(int index) {
		return "audio" + index + ".wav";
	}

    /**
     * Closes the current download file and starts playing it.
     */
    private void playNextAudioFile() {
    	if (sNumAudioBuffers == 0) {
    		mNumWaits += 1;
    		return;
    	}
    	sNumAudioBuffers -= 1;
    	String filename = getAudioFilename(mPlayingAudioNum);
    	mPlayingAudioNum += 1;
    	if (mPlayingAudioNum >= MAX_AUDIO_BUFFERS) {
    		mPlayingAudioNum = 0;
    	}
    	File file = new File(sStorageRoot, filename);
		try {
			mMediaPlayer.reset();
	    	mMediaPlayer.setDataSource(file.getAbsolutePath());
			//mMediaPlayer.setDataSource(in.getFD());
			//mMediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
			mMediaPlayer.prepare();
		} catch (FileNotFoundException e) {
			Log.i(TAG, "Audio file " + file.getAbsolutePath() + ": " + e);
			return;
		} catch (IllegalArgumentException e) {
			Log.i(TAG, "Audio file " + file.getAbsolutePath() + ": " + e);
			return;
		} catch (IllegalStateException e) {
			Log.i(TAG, "Audio file " + file.getAbsolutePath() + ": " + e);
			return;
		} catch (IOException e) {
			Log.i(TAG, "Audio file " + file.getAbsolutePath() + ": " + e);
			return;
		}
        mMediaPlayer.start();
    }

    /**
     * Deletes the files used for buffering the audio stream from Spykee.
     */
    private void deleteAudioFiles() {
    	for (int i = 0; i < MAX_AUDIO_BUFFERS; i++) {
    		String filename = getAudioFilename(i);
    		File file = new File(sStorageRoot, filename);
    		file.delete();
    	}
    }

    /**
     * Writes the next audio packet to a file using the ".wav" format.
     * The input is a stream of signed 16-bit integers (little-endian)
     * audio samples at 16000 Hz. This is converted to to 8-bit unsigned
     * samples (still at 16KHz) so that the playback is slightly faster.
     * @param bytes the input stream of 16-bit audio samples
     * @param len the number of bytes in the array
     */
    static void writeNextAudioFile(byte[] bytes, int len) {
		String filename = getAudioFilename(sDownloadingAudioNum);
    	sDownloadingAudioNum += 1;
    	if (sDownloadingAudioNum >= MAX_AUDIO_BUFFERS) {
    		sDownloadingAudioNum = 0;
    	}
    	File waveFile = new File(sStorageRoot, filename);
		RandomAccessFile output = null;
    	try {
			byte[] samples = new byte[len / 2];
			convert16bit8bit(bytes, samples, len);
			output = new RandomAccessFile(waveFile, "rw");
			output.setLength(0);
			len = len / 2;

			// Fill in the wave header based on the input size.
	    	int2Bytes(len + 36, sWaveHeader, WAVE_CHUNK_SIZE_OFFSET);
			int2Bytes(len, sWaveHeader, WAVE_DATA_SIZE_OFFSET);

			// Write the wave header to the file, followed by the data.
			output.write(sWaveHeader, 0, sWaveHeader.length);
			output.write(samples, 0, len);
		} catch (FileNotFoundException e) {
			Log.i(TAG, waveFile.getAbsolutePath() + ": " + e);
			return;
		} catch (IOException e) {
			Log.i(TAG, waveFile.getAbsolutePath() + ": " + e);
			return;
		} finally {
			try {
				if (output != null) {
					output.close();
				}
			} catch (IOException e) {
				Log.i(TAG, waveFile.getAbsolutePath() + ": " + e);
			}
		}
    }

    private static void convert16bit8bit(byte[] bytes16, byte[] bytes8, int len) {
    	for (int i = 0, j = 0; i < len; i += 2, j += 1) {
    		int val = (bytes16[i+1] << 8) + (bytes16[i] & 0xff);
    		bytes8[j] = (byte) ((val + 0x8000) >> 8);
    	}
    }

    /**
     * Writes the given integer "value" as 4 bytes to the given "bytes" array
     * starting at the given "offset".  The 32-bit integer value is written in
     * little-endian order.
     * @param value the 32-bit integer value to write
     * @param bytes the destination byte array
     * @param offset the index of the first byte to write
     */
    private static void int2Bytes(int value, byte[] bytes, int offset) {
    	bytes[offset] = (byte) (value & 0xff);
    	bytes[offset + 1] = (byte) ((value >> 8) & 0xff);
    	bytes[offset + 2] = (byte) ((value >> 16) & 0xff);
    	bytes[offset + 3] = (byte) ((value >> 24) & 0xff);
    }

    /**
     * Writes the given bytes to the given filename.  The file is put in a
     * directory called "spykee" on the sd card.
     * @param filename the filename to write.
     * @param append if true, then data is written to the end of the file
     * @param bytes the raw data to write, starting at index "offset"
     * @param offset the index of the first byte to write
     * @param len the number of bytes to write
     */
    public static void writeFile(String filename, boolean append, byte[] bytes,
    		int offset, int len) {
		File file = new File(sStorageRoot, filename);
		try {
			FileOutputStream out = new FileOutputStream(file, append);
			out.write(bytes, 0, len);
			out.close();
		} catch (FileNotFoundException e) {
			Log.i(TAG, "Cannot write to " + file.getAbsolutePath());
			return;
		} catch (IOException e) {
			Log.i(TAG, "IO Exception writing to " + file.getAbsolutePath() + ": " + e);
			return;
		}
    }
}
