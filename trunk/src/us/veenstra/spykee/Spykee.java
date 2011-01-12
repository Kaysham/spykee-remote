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

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.UnknownHostException;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.util.Log;

/**
 * This class handles communication with the Spykee robot.  It creates a thread
 * to do the network IO in the background.  It uses the supplied Handler to
 * send messages to the UI thread.
 */
public class Spykee {
	public static final String TAG = "Spykee";
	private Handler mHandler;
	private Socket mSocket;
	private DataInputStream mInput;
	private DataOutputStream mOutput;
	public enum DockState { DOCKED, UNDOCKED, DOCKING };
	private DockState mDockState;

	public static final int SPYKEE_AUDIO = 1;
	public static final int SPYKEE_VIDEO_FRAME = 2;
	public static final int SPYKEE_BATTERY_LEVEL = 3;
	public static final int SPYKEE_DOCK = 16;
	public static final int SPYKEE_DOCK_UNDOCKED = 1;
	public static final int SPYKEE_DOCK_DOCKED = 2;

	private static final int DEFAULT_VOLUME = 50;  // volume is between [0, 100]
	private static final byte[] CMD_LOGIN       = { 'P', 'K', 0x0a, 0 };
	private static final byte[] CMD_UNDOCK      = { 'P', 'K', 0x10, 0, 1, 5 };
	private static final byte[] CMD_DOCK        = { 'P', 'K', 0x10, 0, 1, 6 };
	private static final byte[] CMD_CANCEL_DOCK = { 'P', 'K', 0x10, 0, 1, 7 };
	private static final byte[] CMD_START_VIDEO = { 'P', 'K', 0x0f, 0, 2, 1, 1 };
	private static final byte[] CMD_STOP_VIDEO  = { 'P', 'K', 0x0f, 0, 2, 1, 0 };
	private static final byte[] CMD_START_AUDIO = { 'P', 'K', 0x0f, 0, 2, 2, 1 };
	private static final byte[] CMD_STOP_AUDIO  = { 'P', 'K', 0x0f, 0, 2, 2, 0 };
	private static byte[] mCmdSoundEffect       = { 'P', 'K', 0x07, 0, 1, 0 };
	private static byte[] mCmdSetVolume         = { 'P', 'K', 0x09, 0, 1, DEFAULT_VOLUME };
	private static byte[] mCmdMove              = { 'P', 'K', 0x05, 0, 0x02, 0, 0 };
	
	private int mForwardSpeed = 100;
	private int mBackwardSpeed = 50;
	private int mTurningSpeed = 15;
	
	// The number of characters decoded per line in the hex dump
	private static final int CHARS_PER_LINE = 32;
	
	// After this many characters in the hex dump, an extra space is inserted
	// (this must be a power of two).
	private static final int EXTRA_SPACE_FREQ = 8;
	private static final int EXTRA_SPACE_MASK = EXTRA_SPACE_FREQ - 1;

	// Create a single Runnable that we can reuse for stopping the motor.
	private MotorStopper mMotorStopper = new MotorStopper();

	// Keep track of the time that we want to stop the motor. This allows us
	// to keep the motor running smoothly if another motor command arrives
	// before the time to stop the motor.
	private long mStopMotorTime;

	private int mImageFileNumber;
	private static final int NUM_IMAGE_FILES = 1000;

	public Spykee(Handler handler) {
		mHandler = handler;
	}

	public void connect(String host, int port, String login, String password)
	        throws UnknownHostException, IOException {
		Log.d(TAG, "connecting to " + host + ":" + port);
		mSocket = new Socket(host, port);
		mOutput = new DataOutputStream(mSocket.getOutputStream());
		mInput = new DataInputStream(mSocket.getInputStream());
		sendLogin(login, password);
		readLoginResponse();
		startNetworkReaderThread();
	}

	public void close() {
		try {
			if (mOutput != null) {
				mOutput.close();
				mInput.close();
				mSocket.close();
			}
		} catch (IOException e) {
		}
	}

	private void sendLogin(String login, String password) throws IOException {
		int len = CMD_LOGIN.length + login.length() + password.length() + 3;
		byte[] bytes = new byte[len];
		System.arraycopy(CMD_LOGIN, 0, bytes, 0, CMD_LOGIN.length);
		int pos = CMD_LOGIN.length;
		bytes[pos++] = (byte) (login.length() + password.length() + 2);
		bytes[pos++] = (byte) login.length();
		System.arraycopy(login.getBytes(), 0, bytes, pos, login.length());
		pos += login.length();
		bytes[pos++] = (byte) password.length();
		System.arraycopy(password.getBytes(), 0, bytes, pos, password.length());
		showBuffer("send", bytes, bytes.length);
		sendBytes(bytes);
	}

	private void readLoginResponse() throws IOException {
		byte[] bytes = new byte[2048];
		int num = readBytes(bytes, 0, 5);
		showBuffer("recv", bytes, num);

		// The fifth byte is the number of remaining bytes to read
		int len = bytes[4];
		num = readBytes(bytes, 0, len);
		showBuffer("recv", bytes, num);
		if (len < 8) {
			return;
		}

		int pos = 1;
		int nameLen = bytes[pos++];
		String name1 = new String(bytes, pos, nameLen, "ISO-8859-1");
		pos += nameLen;
		nameLen = bytes[pos++];
		String name2 = new String(bytes, pos, nameLen, "ISO-8859-1");
		pos += nameLen;
		nameLen = bytes[pos++];
		String name3 = new String(bytes, pos, nameLen, "ISO-8859-1");
		pos += nameLen;
		nameLen = bytes[pos++];
		String version = new String(bytes, pos, nameLen, "ISO-8859-1");
		pos += nameLen;
		if (bytes[pos] == 0) {
			mDockState = DockState.DOCKED;
		} else {
			mDockState = DockState.UNDOCKED;
		}
		Log.i(TAG, name1 + " " + name2 + " " + name3 + " " + version + " docked: " + mDockState);
	}

	public void moveForward() {
		mCmdMove[5] = (byte) mForwardSpeed;
		mCmdMove[6] = (byte) mForwardSpeed;
		try {
			sendBytes(mCmdMove);
			stopMotorAfterDelay(300);
		} catch (IOException e) {
		}
	}

	public void moveBackward() {
		mCmdMove[5] = (byte) (255 - mBackwardSpeed);
		mCmdMove[6] = (byte) (255 - mBackwardSpeed);
		try {
			showBuffer("moveBack", mCmdMove, mCmdMove.length);
			sendBytes(mCmdMove);
			stopMotorAfterDelay(200);
		} catch (IOException e) {
		}
	}

	public void moveLeft() {
		mCmdMove[5] = (byte) (255 - mTurningSpeed);
		mCmdMove[6] = (byte) mTurningSpeed;
		try {
			sendBytes(mCmdMove);
			stopMotorAfterDelay(200);
		} catch (IOException e) {
		}
	}

	public void moveRight() {
		mCmdMove[5] = (byte) mTurningSpeed;
		mCmdMove[6] = (byte) (255 - mTurningSpeed);
		try {
			sendBytes(mCmdMove);
			stopMotorAfterDelay(200);
		} catch (IOException e) {
		}
	}

	public void stopMotor() {
		mCmdMove[5] = 0;
		mCmdMove[6] = 0;
		try {
			sendBytes(mCmdMove);
		} catch (IOException e) {
		}
	}

	private void stopMotorAfterDelay(long delayMillis) {
		mStopMotorTime = SystemClock.uptimeMillis() + delayMillis;
		mHandler.postDelayed(mMotorStopper, delayMillis);
	}

	private class MotorStopper implements Runnable {
		public void run() {
			// Check the time so that we don't stop the motor if another
			// motor command arrived after the one that posted this runnable.
			long currentTime = SystemClock.uptimeMillis();
			if (currentTime >= mStopMotorTime) {
				stopMotor();
			}
		}
	}

	public void activate() {
		if (mDockState == DockState.DOCKED) {
			undock();
		}
		startVideo();
		startAudio();
		setVolume(DEFAULT_VOLUME);
	}

	public DockState getDockState() {
		return mDockState;
	}

	public void dock() {
		try {
			sendBytes(CMD_DOCK);
			mDockState = DockState.DOCKING;
		} catch (IOException e) {
		}
	}

	public void undock() {
		try {
			sendBytes(CMD_UNDOCK);
			mDockState = DockState.UNDOCKED;
		} catch (IOException e) {
		}
	}

	public void cancelDock() {
		try {
			sendBytes(CMD_CANCEL_DOCK);
			mDockState = DockState.UNDOCKED;
		} catch (IOException e) {
		}
	}

	public void setVolume(int volume) {
		mCmdSetVolume[5] = (byte) volume;
		try {
			sendBytes(mCmdSetVolume);
		} catch (IOException e) {
		}
	}

	public void startVideo() {
		try {
			sendBytes(CMD_START_VIDEO);
		} catch (IOException e) {
		}
	}

	public void stopVideo() {
		try {
			sendBytes(CMD_STOP_VIDEO);
		} catch (IOException e) {
		}
	}

	public void startAudio() {
		try {
			sendBytes(CMD_START_AUDIO);
		} catch (IOException e) {
		}
	}

	public void stopAudio() {
		try {
			sendBytes(CMD_STOP_AUDIO);
		} catch (IOException e) {
		}
	}

	public void playSoundAlarm() {
		mCmdSoundEffect[5] = 0;
		try {
			sendBytes(mCmdSoundEffect);
		} catch (IOException e) {
		}
	}

	public void playSoundBomb() {
		mCmdSoundEffect[5] = 1;
		try {
			sendBytes(mCmdSoundEffect);
		} catch (IOException e) {
		}
	}

	public void playSoundLazer() {
		mCmdSoundEffect[5] = 2;
		try {
			sendBytes(mCmdSoundEffect);
		} catch (IOException e) {
		}
	}

	public void playSoundAhAhAh() {
		mCmdSoundEffect[5] = 3;
		try {
			sendBytes(mCmdSoundEffect);
		} catch (IOException e) {
		}
	}

	public void playSoundEngine() {
		mCmdSoundEffect[5] = 4;
		try {
			sendBytes(mCmdSoundEffect);
		} catch (IOException e) {
		}
	}

	public void playSoundRobot() {
		mCmdSoundEffect[5] = 5;
		try {
			sendBytes(mCmdSoundEffect);
		} catch (IOException e) {
		}
	}

	public void playSoundCustom1() {
		mCmdSoundEffect[5] = 6;
		try {
			sendBytes(mCmdSoundEffect);
		} catch (IOException e) {
		}
	}

	public void playSoundCustom2() {
		mCmdSoundEffect[5] = 7;
		try {
			sendBytes(mCmdSoundEffect);
		} catch (IOException e) {
		}
	}

	private void startNetworkReaderThread() {
		new Thread(new Runnable() {
			public void run() {
				readFromSpykee(); 
			}
		}).start();
	}

	/**
	 * Reads network packets from the Spykee robot. This runs in a background
	 * thread.
	 */
	private void readFromSpykee() {
		Message msg;
		byte[] bytes = new byte[8192];
		while (true) {
			int num, len = 0;
			int cmd = -1;
			byte[] frame = null;
			try {
				num = readBytes(bytes, 0, 5);
				if (num == 5 && (bytes[0] & 0xff) == 'P' && (bytes[1] & 0xff) == 'K') {
					cmd = bytes[2] & 0xff;
					len = ((bytes[3] & 0xff) << 8) | (bytes[4] & 0xff);
					Log.i(TAG, "cmd: " + cmd + " len: " + len);
					switch (cmd) {
					case SPYKEE_BATTERY_LEVEL:
						num += readBytes(bytes, 5, len);
						int level = bytes[5] & 0xff;
						msg = mHandler.obtainMessage(SPYKEE_BATTERY_LEVEL);
						msg.arg1 = level;
						mHandler.sendMessage(msg);
						break;
					case SPYKEE_VIDEO_FRAME:
						// Avoid an extra data copy by reading directly into
						// the video frame
						frame = new byte[len];
						num += readBytes(frame, 0, len);
						//showBuffer("video", frame, len);
						//writeNextImageFile(frame, len);
		    			Bitmap bitmap = BitmapFactory.decodeByteArray(frame, 0, len);
		    			if (bitmap == null) {
		    				break;
		    			}
						msg = mHandler.obtainMessage(SPYKEE_VIDEO_FRAME);
						msg.obj = bitmap;
						mHandler.sendMessage(msg);
						break;
					case SPYKEE_AUDIO:
						// Avoid an extra data copy by reading directly into
						// the audio buffer
						frame = new byte[len];
						num += readBytes(frame, 0, len);
						Main.writeNextAudioFile(frame, len);
						//showBuffer("audio", frame, len);
						msg = mHandler.obtainMessage(SPYKEE_AUDIO);
						mHandler.sendMessage(msg);
						break;
					case SPYKEE_DOCK:
						num += readBytes(bytes, 5, len);
						showBuffer("recv", bytes, num);
						int val = bytes[5] & 0xff;
						if (val == SPYKEE_DOCK_DOCKED) {
							mDockState = DockState.DOCKED;
						} else if (val == SPYKEE_DOCK_UNDOCKED) {
							mDockState = DockState.UNDOCKED;
						}
						msg = mHandler.obtainMessage(SPYKEE_DOCK);
						msg.arg1 = val;
						mHandler.sendMessage(msg);
						break;
					default:
						num += readBytes(bytes, 5, len);
						showBuffer("recv", bytes, num);
					}
				} else {
					Log.i(TAG, "unexpected data, num: " + num);
					showBuffer("recv", bytes, num);
				}
			} catch (IOException e) {
				Log.i(TAG, "IO exception: " + e);
				break;
			}
		}
	}

	/**
	 * Sends a command to Spykee.
	 * @param bytes the byte array containing the Spykee command
	 * @throws IOException
	 */
	private void sendBytes(byte[] bytes) throws IOException {
		mOutput.write(bytes);
	}

	/**
	 * Tries to read "len" bytes into the given byte array. Returns the number
	 * of bytes actually read.
	 *
	 * @param bytes the destination byte array
	 * @param offset the starting offset into the byte array for the first byte
	 * @param len the number of bytes to read
	 * @return the actual number of bytes read
	 * @throws IOException
	 */
	private int readBytes(byte[] bytes, int offset, int len) throws IOException {
		int remaining = len;
		while (remaining > 0) {
			int numRead = mInput.read(bytes, offset, remaining);
			//Log.i(TAG, "readBytes(): " + numRead);
			if (numRead <= 0) {
				break;
			}
			offset += numRead;
			remaining -= numRead;
		}
		return len - remaining;
	}

	/**
	 * Displays the bytes in the given array, both in hex and in ascii.
	 *
	 * @param bytes the array of bytes
	 */
	private void showBuffer(String tag, byte[] bytes, int len) {
		if (len > 256) {
			len = 256;
		}
		int charsPerLine = CHARS_PER_LINE;
		if (len < charsPerLine) {
			charsPerLine = len;
		}
		StringBuilder builder = new StringBuilder();
		for (int i = 0; i < len; i += charsPerLine) {
			builder.append(tag + " ");
			for (int j = 0; j < charsPerLine; j++) {
				if (i + j >= len) {
					break;
				}
				byte val = bytes[i + j];
				builder.append(String.format("%02x ", val));
				if ((j & EXTRA_SPACE_MASK) == EXTRA_SPACE_MASK) {
					builder.append(" ");
				}
			}
			if (len - i < charsPerLine) {
				for (int j = len - i; j < charsPerLine; j++) {
					builder.append("   ");
					if ((j & EXTRA_SPACE_MASK) == EXTRA_SPACE_MASK) {
						builder.append(" ");
					}
				}
			}

			// Put an extra space before the ascii character dump
			builder.append(" ");
			for (int j = 0; j < charsPerLine; j++) {
				if (i + j >= len) {
					break;
				}
				byte val = bytes[i + j];
				if (val < 0x20 || val > 0x7e) {
					val = '.';
				}
				builder.append(String.format("%c", val));
				if ((j & EXTRA_SPACE_MASK) == EXTRA_SPACE_MASK) {
					builder.append(" ");
				}
			}
			Log.i(TAG, builder.toString());
			builder.setLength(0);
		}
	}

	private void writeNextImageFile(byte[] bytes, int len) {
		String filename = String.format("image%03d.jpg", mImageFileNumber);
		mImageFileNumber += 1;
		if (mImageFileNumber >= NUM_IMAGE_FILES) {
			mImageFileNumber = 0;
		}
		Main.writeFile(filename, false /* append */, bytes, 0 /* offset */, len);
	}
}
