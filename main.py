from flask import Flask, request, jsonify
import socket
import threading
import time
from zeroconf import ServiceInfo, Zeroconf
import os
from datetime import datetime
import logging

app = Flask(__name__)

# Configure logging
logging.basicConfig(level=logging.INFO,
                   format='%(asctime)s - %(levelname)s - %(message)s')

# Store the latest image count
current_image_count = 0

# Create upload directory
UPLOAD_DIR = 'uploaded_images'
if not os.path.exists(UPLOAD_DIR):
    os.makedirs(UPLOAD_DIR)

def register_service():
    # Get local IP
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(('8.8.8.8', 1))  # Doesn't actually connect
        local_ip = s.getsockname()[0]
    except Exception:
        local_ip = '127.0.0.1'
    finally:
        s.close()

    # Register zeroconf service
    zeroconf = Zeroconf()
    service_info = ServiceInfo(
        "_imagecount._tcp.local.",
        "ImageCountServer._imagecount._tcp.local.",
        addresses=[socket.inet_aton(local_ip)],
        port=8000,
        properties={},
        server=f"ImageCountServer.local."
    )
    
    try:
        zeroconf.register_service(service_info)
        logging.info(f"Service registered. Server running at {local_ip}:8000")
        return zeroconf, service_info
    except Exception as e:
        logging.error(f"Failed to register service: {e}")
        return None, None

@app.route('/update_count', methods=['POST'])
def update_count():
    global current_image_count
    try:
        data = request.get_json()
        
        if 'count' in data:
            current_image_count = data['count']
            logging.info(f"Received new image count: {current_image_count}")
            return jsonify({"status": "success", "count": current_image_count})
        else:
            return jsonify({"status": "error", "message": "No count provided"}), 400
    except Exception as e:
        logging.error(f"Error in update_count: {e}")
        return jsonify({"status": "error", "message": str(e)}), 500

@app.route('/get_count', methods=['GET'])
def get_count():
    return jsonify({"count": current_image_count})

@app.route('/upload_image', methods=['POST'])
def upload_image():
    try:
        if 'image' not in request.files:
            return jsonify({"status": "error", "message": "No image file in request"}), 400

        image_file = request.files['image']
        if image_file.filename == '':
            return jsonify({"status": "error", "message": "No selected file"}), 400

        # Upload all images to a single directory
        file_path = os.path.join(UPLOAD_DIR, image_file.filename)
        
        # Ensure file name is unique by appending timestamp if necessary
        if os.path.exists(file_path):
            timestamp = datetime.now().strftime('%Y%m%d_%H%M%S')
            file_name, file_extension = os.path.splitext(image_file.filename)
            new_file_name = f"{file_name}_{timestamp}{file_extension}"
            file_path = os.path.join(UPLOAD_DIR, new_file_name)

        # Save the file
        image_file.save(file_path)
        
        file_size = os.path.getsize(file_path)
        logging.info(f"Saved image: {file_path} (Size: {file_size / 1024:.2f} KB)")

        return jsonify({
            "status": "success",
            "message": "Image uploaded successfully",
            "file_path": file_path,
            "size": file_size
        })

    except Exception as e:
        logging.error(f"Error in upload_image: {e}")
        return jsonify({"status": "error", "message": str(e)}), 500

@app.route('/status', methods=['GET'])
def get_status():
    try:
        # Get total number of images downloaded
        total_images = 0
        total_size = 0
        
        for root, dirs, files in os.walk(UPLOAD_DIR):
            for file in files:
                if file.lower().endswith(('.png', '.jpg', '.jpeg', '.gif', '.bmp')):
                    total_images += 1
                    total_size += os.path.getsize(os.path.join(root, file))

        return jsonify({
            "status": "success",
            "total_images_received": total_images,
            "total_size_mb": total_size / (1024 * 1024),
            "upload_directory": UPLOAD_DIR,
            "reported_image_count": current_image_count
        })

    except Exception as e:
        logging.error(f"Error in get_status: {e}")
        return jsonify({"status": "error", "message": str(e)}), 500

def cleanup():
    logging.info("Starting cleanup...")
    # Add any cleanup tasks here
    pass

if __name__ == '__main__':
    # Start service registration in a separate thread
    zeroconf, service_info = register_service()
    
    try:
        # Run the Flask app
        app.run(host='0.0.0.0', port=8000, threaded=True)
    except Exception as e:
        logging.error(f"Server error: {e}")
    finally:
        # Cleanup
        cleanup()
        if zeroconf and service_info:
            logging.info("Unregistering service...")
            zeroconf.unregister_service(service_info)
            zeroconf.close()