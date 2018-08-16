
/**
 * By BingLi224
 *
 * @author	BingLi224
 *
 * @version	2018.08.11
 *
 * 11:28 THA 11/08/2018
 *
 * Get data from source, save it to shared variable with synchronization, and broadcast to multiple clients through sockets.
 *
 * Thread 1:
 * 	Generate data, and share to shared variable.
 * Thread 2:
 * 	Wait for new data, and broadcast to the clients through sockets.
 * Thread 3:
 * 	Wait for new client via socket, and add to the client list.
 *
 * Reference:
 *  https://www.journaldev.com/1037/java-thread-wait-notify-and-notifyall-example
 *  http://tutorials.jenkov.com/java-nio/selectors.html
 *
 * @version	2018.08.16
 *
 * 00:39 THA 17/08/2018
 *
 * Fix: remove from <code>clientsOut</code> after lambda is done if got IOException
 */

import java.net.Socket;
import java.net.ServerSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.IOException;

import java.nio.channels.Selector;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

import java.util.HashSet;
import java.util.Arrays;
import java.util.Iterator;

import java.util.Random;

public class SelectorSocketThreadTester
{
	// socket selector
	private Selector selector;

	// server for data receivers
	private ServerSocket dataOutServer;

	// data for the clients
	private String cache;

	// synchronization monitor
	private final Object MONITOR = new Object ( );

	// list of clients
	private HashSet <OutputStream> clientsOut;

	public SelectorSocketThreadTester ( )
		throws  IOException
	{
		selector = Selector.open ( );

		clientsOut = new HashSet <OutputStream> ();

		createBroadcaster ( );
		createGenerator ( );
		createClientListener ( );
	}

	public void createClientListener ( )
	{
		// client listener
		new Thread (
			new Runnable ( ) {
				@Override
				public void run ( )
				{
					// create the server to wait for the data receivers
					try {
						ServerSocketChannel schannel = ServerSocketChannel.open ( );

						schannel.bind ( new InetSocketAddress ( "localhost", 0 ), 50 );

						// unblockable
						schannel.configureBlocking ( false );

						// get the socket
						dataOutServer = schannel.socket ( );

						// register to the selector
						schannel.register ( selector, SelectionKey.OP_ACCEPT );
						System.out.println ( "data out = " + schannel );
					} catch ( IOException ioe ) { } // try: create server-to-data-receivers socket

					try {
						while ( true ) {
							// wait for new client
							selector.select ( );

							// accept and connect to the new client
							selector.selectedKeys ( ).iterator ( ).forEachRemaining ( selected ->
								{
									System.out.println ( selected.channel ( ).toString ( ) );
									if ( selected.isAcceptable ( ) )
									{
										try {
											// connect to the client
											SocketChannel schannel = ( (ServerSocketChannel) selected.channel ( ) ).accept ( );

											// get the channel
											System.out.println ( "new client=" + schannel.toString ( ) );

											// save the output of the client
											OutputStream out = schannel.socket ( ).getOutputStream ( );
											clientsOut.add ( out );

											// send the current cache to the client
											synchronized ( MONITOR ) {
												try {
													// wait for data update, if any
													MONITOR.wait ( 500 );
												} catch ( InterruptedException ie ) { }
												finally {
													if ( cache != null )
														out.write ( cache.getBytes ( ) );
												}
											} // sync: cache update?
										} // try: get new client
										catch ( IOException ioe ) { ioe.printStackTrace ( ); }
									} // if: accept mode
									else if ( selected.isReadable ( ) )
									{
										// got new data
										try {
											String dat = ( (BufferedReader)( selected.attachment ( ) ) ).readLine ( );
											if ( dat != null && dat != cache )
											{
												cache = dat;
											}
										} catch ( IOException ioe ) { ioe.printStackTrace ( ); }
									}

									//selects.remove ( );
								} // for: each selected socket
							);
						} // while: 1
					} catch ( IOException ioe ) { } // try: create server-to-data-receivers socket
				} // function: send the data to the clients
			} // class: Runnable
		).start ( );
	} // function: create client listener

	public void createBroadcaster ( )
	{
		// data broadcaster
		new Thread (
			new Runnable ( ) {
				@Override
				public void run ( )
				{
					try {
						Random rnd = new Random ( );
						String old = null;

						while ( true ) {
							synchronized ( MONITOR ) {
								MONITOR.wait ( );

								// if new data
								if ( cache.length ( ) > 0 && ! cache.equals ( old ) )
								{
									final byte [] shared = cache.getBytes ( );

									HashSet <OutputStream> closed = new HashSet <OutputStream> ( );

									// send data to all clients
									clientsOut.iterator ( ).forEachRemaining ( o -> {
										try {
											// send data to the client
											o.write ( shared );
										} catch ( IOException ioe ) {
											ioe.printStackTrace ( );

											// disconnect
											try {
												o.close ( );
											} catch ( IOException ioe1 ) { }

											closed.add ( o );
										}
									} );

									// remove the disconnected onces
									if ( closed.size ( ) > 0 )
										clientsOut.removeAll ( closed );

									// remember the latest data
									old = cache;
								} // if: new data
							} // sync: shared data
						} // while: 1
					} // try: send the data to the clients
					catch ( InterruptedException ie ) { ie.printStackTrace ( ); }
				} // function: send the data to the clients
			} // class: Runnable
		).start ( );
	} // function: create data broadcaster

	public void createGenerator ( )
	{
		// data generator
		new Thread (
			new Runnable ( ) {
				@Override
				public void run ( )
				{
					Random rnd = new Random ( );

					// generate random data
					while ( true ) {
						synchronized ( MONITOR ) {
							cache = "";
							for ( int idx = 0; idx < 1000; idx ++ )
								cache += rnd.nextInt ( );
							cache += "\n";
							MONITOR.notifyAll ( );
						} // sync: shared data

						try { Thread.sleep ( 100 ); } catch ( InterruptedException ie ) { ie.printStackTrace ( ); }
					} // while: 1
				} // function: send the data to the clients
			} // class: Runnable
		).start ( );
	} // function: data generator

	/**
	 * Return the port of the server
	 *
	 * @return	Port of server for clients, or -1 if server is not created.
	 */
	public int getPort ( )
	{
		if ( dataOutServer != null )
		{
			// return the port of the server
			return dataOutServer.getLocalPort ( );
		}
		else
		{
			return -1;
		}
	} // function: get port of server for clients

	public static void main ( String [] argv )
	{
		try {
			SelectorSocketThreadTester tester = new SelectorSocketThreadTester ( );

			int port = -1;
			// wait for the server to be created
			while ( ( port = tester.getPort ( ) ) < 0 )
			{
				try { Thread.sleep ( 1000 ); } catch ( InterruptedException ie ) { }
			}

			// create the clients
			Arrays.asList (
				new Socket ( ),
				new Socket ( ),
				new Socket ( ),
				new Socket ( ),
				new Socket ( )
			).parallelStream ( ).forEach ( sock -> {
				try {
					sock.connect (
						new InetSocketAddress ( "localhost", tester.getPort ( ) ),
						50000
					);

					// set timeout to wait for new data
					sock.setSoTimeout ( 50000 );

					System.out.println ( sock.getLocalPort ( ) + "<<< new" );

					String buffer;
					BufferedReader in = new BufferedReader ( new InputStreamReader (
								sock.getInputStream ( )
						) );
					while ( ! sock.isClosed ( ) )
					{
						try {
							// if got new data
							if ( ( buffer = in.readLine ( ) ) != null )
							{
								// show new data
								if ( buffer.length ( ) < 20 )
									System.out.println ( sock.getLocalPort ( ) + "<<< [" + buffer.length ( ) + "] " + buffer );
								else
									System.out.println ( sock.getLocalPort ( ) + "<<< [" + buffer.length ( ) + "] " + buffer.substring ( 0, 30 ) );
							}
							else
							{
								// end of connection
								System.out.println ( sock.getLocalPort ( ) + "<<<x" );
								sock.close ( );
							}
						} catch ( IOException ioe ) { ioe.printStackTrace ( ); }
					} // while: 1
				} catch ( IOException ioe ) { ioe.printStackTrace ( ); }
				finally {
					try { sock.close ( ); } catch ( IOException ioe ) { }
				}
			} ); // foreach: new client sockets
		}
		catch ( IOException ioe ) { ioe.printStackTrace ( ); }
	}
}
